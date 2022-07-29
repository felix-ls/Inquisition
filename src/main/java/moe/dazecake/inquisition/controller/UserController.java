package moe.dazecake.inquisition.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zjiecode.wxpusher.client.WxPusher;
import com.zjiecode.wxpusher.client.bean.CreateQrcodeReq;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import moe.dazecake.inquisition.annotation.UserLogin;
import moe.dazecake.inquisition.entity.AccountEntity;
import moe.dazecake.inquisition.entity.CDKEntity;
import moe.dazecake.inquisition.entity.LogEntity;
import moe.dazecake.inquisition.entity.NoticeEntitySet.NoticeEntity;
import moe.dazecake.inquisition.entity.NoticeEntitySet.WXUID;
import moe.dazecake.inquisition.entity.NoticeEntitySet.WechatCallbackEntity;
import moe.dazecake.inquisition.mapper.AccountMapper;
import moe.dazecake.inquisition.mapper.CDKMapper;
import moe.dazecake.inquisition.mapper.LogMapper;
import moe.dazecake.inquisition.util.DynamicInfo;
import moe.dazecake.inquisition.util.JWTUtils;
import moe.dazecake.inquisition.util.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Tag(name = "用户接口")
@ResponseBody
@RestController
public class UserController {

    @Resource
    DynamicInfo dynamicInfo;

    @Resource
    AccountMapper accountMapper;

    @Resource
    LogMapper logMapper;

    @Resource
    CDKMapper cdkMapper;

    @Value("${wx-pusher.app-token}")
    String appToken;

    @Value("${wx-pusher.enable}")
    boolean enableWxPusher;

    @Value("${cron:'0 0 4,12,20 * * ?'}")
    String cron;

    @Operation(summary = "创建我的账号")
    @PostMapping("/createUser")
    public Result<String> createUser(String cdk, @RequestBody AccountEntity accountEntity) {
        Result<String> result = new Result<>();

        var cdkEntity = cdkMapper.selectOne(Wrappers.<CDKEntity>lambdaQuery().eq(CDKEntity::getCdk, cdk));
        if (cdkEntity == null) {
            result.setCode(403);
            result.setMsg("CDK不存在");
            return result;
        } else if (cdkEntity.getUsed() == 1) {
            result.setCode(403);
            result.setMsg("CDK已使用");
            return result;
        }

        cdkEntity.setUsed(1);
        cdkMapper.updateById(cdkEntity);

        accountEntity.setId(0L);
        accountEntity.setExpireTime(LocalDateTime.now());
        activateCDK(accountEntity, cdkEntity);
        accountMapper.insert(accountEntity);
        return result.setCode(200).setMsg("success").setData(null);
    }

    @Operation(summary = "登陆我的账号")
    @PostMapping("/userLogin")
    public Result<HashMap<String, String>> userLogin(String username, String password) {
        Result<HashMap<String, String>> result = new Result<>();

        if (username == null || password == null) {
            return result.setCode(403)
                    .setMsg("账号或密码为空")
                    .setData(null);
        }

        var account = accountMapper.selectOne(
                Wrappers.<AccountEntity>lambdaQuery()
                        .eq(AccountEntity::getAccount, username)
                        .eq(AccountEntity::getPassword, password)
        );

        if (account != null) {
            return result.setCode(200)
                    .setMsg("登陆成功")
                    .setData(new HashMap<>() {
                        {
                            put("token", JWTUtils.generateTokenForUser(account));
                        }
                    });
        } else {
            return result.setCode(404)
                    .setMsg("账号或密码错误")
                    .setData(null);
        }
    }

    @UserLogin
    @Operation(summary = "查询自己的账号")
    @GetMapping("/showMyAccount")
    public Result<AccountEntity> showMyAccount(@RequestHeader("Authorization") String token) {
        Result<AccountEntity> result = new Result<>();
        result.setData(new AccountEntity());

        var account = accountMapper.selectOne(
                Wrappers.<AccountEntity>lambdaQuery()
                        .eq(AccountEntity::getId, JWTUtils.getId(token))
        );
        result.setCode(200)
                .setMsg("success")
                .setData(account);

        return result;
    }

    @UserLogin
    @Operation(summary = "更新自己的账号")
    @PostMapping("/updateMyAccount")
    public Result<String> updateMyAccount(@RequestHeader("Authorization") String token,
                                          @RequestBody AccountEntity accountEntity) {
        Result<String> result = new Result<>();

        var account = accountMapper.selectOne(
                Wrappers.<AccountEntity>lambdaQuery()
                        .eq(AccountEntity::getId, JWTUtils.getId(token))
        );
        if (account != null) {
            String taskType = account.getTaskType();
            LocalDateTime expireTime = account.getExpireTime();
            account = accountEntity;
            account.setId(JWTUtils.getId(token));
            account.setTaskType(taskType);
            account.setExpireTime(expireTime);

            accountMapper.updateById(account);

            result.setCode(200)
                    .setMsg("success");

        } else {
            result.setCode(403)
                    .setMsg("Unable to update a non-existent account");

        }
        result.setData(null);
        return result;
    }

    @UserLogin
    @Operation(summary = "查询我的日志")
    @GetMapping("/showMyLog")
    public Result<ArrayList<LogEntity>> showMyLog(@RequestHeader("Authorization") String token, Long current,
                                                  Long size) {
        Result<ArrayList<LogEntity>> result = new Result<>();
        result.setData(new ArrayList<>());

        //降序分页查找
        var data = logMapper.selectPage(new Page<>(current, size), Wrappers.<LogEntity>lambdaQuery()
                .eq(LogEntity::getAccount, JWTUtils.getUsername(token))
                .orderByDesc(LogEntity::getId));
        result.setCode(200)
                .setMsg("success")
                .getData()
                .addAll(data.getRecords());

        return result;
    }

    @UserLogin
    @Operation(summary = "查询我状态")
    @GetMapping("/showMyStatus")
    public Result<HashMap<String, String>> showMyStatus(@RequestHeader("Authorization") String token) {
        Result<HashMap<String, String>> result = new Result<>();
        result.setData(new HashMap<>());

        var id = JWTUtils.getId(token);
        AtomicBoolean flag = new AtomicBoolean(false);
        AtomicInteger index = new AtomicInteger(0);

        dynamicInfo.getFreeTaskList().forEach(
                it -> {
                    if (Objects.equals(it.getId(), id)) {
                        flag.set(true);
                        if (index.get() != 0) {
                            result.getData().put("msg", "前方还有" + index + "个账号");
                        } else {
                            result.getData().put("msg", "等待空闲设备作战，请勿顶号");
                        }
                    }
                    index.getAndIncrement();
                }
        );

        dynamicInfo.getLockTaskList().forEach(
                (key, value) -> value.forEach(
                        (account, time) -> {
                            if (Objects.equals(account.getId(), id)) {
                                flag.set(true);
                                result.getData().put("msg", "正在作战中，请勿顶号");
                            }
                        }
                )
        );

        if (!flag.get()) {
            LocalDateTime nextTime = CronExpression.parse(cron).next(LocalDateTime.now());
            assert nextTime != null;
            String nextTimeStr = nextTime.format(DateTimeFormatter.ofPattern("HH:mm"));
            result.getData().put("msg", "空闲中,下一轮作战将于" + nextTimeStr + "开始");
        }

        return result;
    }

    @UserLogin
    @Operation(summary = "使用CDK")
    @PostMapping("/useCDK")
    public Result<String> useCDK(@RequestHeader("Authorization") String token, String cdk) {
        Result<String> result = new Result<>();

        var cdkEntity = cdkMapper.selectOne(Wrappers.<CDKEntity>lambdaQuery().eq(CDKEntity::getCdk, cdk));
        if (cdkEntity == null) {
            result.setCode(403);
            result.setMsg("CDK不存在");
            return result;
        } else if (cdkEntity.getUsed() == 1) {
            result.setCode(403);
            result.setMsg("CDK已使用");
            return result;
        }

        cdkEntity.setUsed(1);
        cdkMapper.updateById(cdkEntity);

        var account = accountMapper.selectOne(
                Wrappers.<AccountEntity>lambdaQuery()
                        .eq(AccountEntity::getId, JWTUtils.getId(token))
        );
        if (account != null) {
            activateCDK(account, cdkEntity);
            accountMapper.updateById(account);
        }
        return result.setCode(200).setMsg("success").setData(null);
    }

    @UserLogin
    @Operation(summary = "获取微信推送二维码")
    @GetMapping("/getWechatQRCode")
    public Result<String> getWechatQRCode(@RequestHeader("Authorization") String token) {
        Result<String> result = new Result<>();
        if (enableWxPusher) {
            var account = accountMapper.selectOne(
                    Wrappers.<AccountEntity>lambdaQuery()
                            .eq(AccountEntity::getId, JWTUtils.getId(token))
            );
            if (account == null) {
                result.setCode(403);
                result.setMsg("账号不存在");
                return result;
            }
            CreateQrcodeReq createQrcodeReq = new CreateQrcodeReq();
            createQrcodeReq.setAppToken(appToken);
            createQrcodeReq.setExtra(String.valueOf(JWTUtils.getId(token)));
            createQrcodeReq.setValidTime(3600);
            var qrcode = WxPusher.createAppTempQrcode(createQrcodeReq);
            if (qrcode.isSuccess()) {
                return result.setCode(200).setMsg("success").setData(qrcode.getData().getUrl());
            } else {
                return result.setCode(403).setMsg("获取二维码失败");
            }
        } else {
            return result.setCode(403).setMsg("未开启微信推送");
        }
    }

    @Operation(summary = "获取微信推送回调")
    @PostMapping("/getWechatCallback")
    public void getWechatCallback(@RequestBody WechatCallbackEntity wechatCallback) {
        if (enableWxPusher) {
            AccountEntity accountEntity = accountMapper.selectById(wechatCallback.getData().getExtra());
            if (accountEntity != null) {
                var noticeEntity = accountEntity.getNotice();
                if (noticeEntity == null) {
                    noticeEntity = new NoticeEntity();
                }
                if (noticeEntity.getWxUID() == null) {
                    noticeEntity.setWxUID(new WXUID());
                }
                noticeEntity.getWxUID().setText(wechatCallback.getData().getUid());
                noticeEntity.getWxUID().setEnable(true);
                accountEntity.setNotice(noticeEntity);
                accountMapper.updateById(accountEntity);
            }
        }
    }

    @UserLogin
    @Operation(summary = "获取通知状态")
    @GetMapping("/getNoticeStatus")
    public Result<NoticeEntity> getNoticeStatus(@RequestHeader("Authorization") String token) {
        Result<NoticeEntity> result = new Result<>();
        var account = accountMapper.selectOne(
                Wrappers.<AccountEntity>lambdaQuery()
                        .eq(AccountEntity::getId, JWTUtils.getId(token))
        );

        if (account.getNotice() == null) {
            account.setNotice(new NoticeEntity());
            account.getNotice().getWxUID().setEnable(false);
            account.getNotice().getWxUID().setText("");
            account.getNotice().getMail().setEnable(false);
            account.getNotice().getMail().setText("");
            account.getNotice().getQq().setEnable(false);
            account.getNotice().getQq().setText("");
            accountMapper.updateById(account);
        }

        result.setCode(200);
        result.setMsg("success");
        result.setData(account.getNotice());
        return result;
    }


    private void activateCDK(AccountEntity accountEntity, CDKEntity cdkEntity) {
        if (accountEntity.getExpireTime().isBefore(LocalDateTime.now())) {
            accountEntity.setExpireTime(LocalDateTime.now());
        }
        switch (cdkEntity.getType()) {
            case "daily":
                accountEntity.setExpireTime(accountEntity.getExpireTime().plusDays(cdkEntity.getParam()));
                break;
            case "rouge_level":
                accountEntity.setExpireTime(accountEntity.getExpireTime().plusDays(2));
                accountEntity.getConfig().getRogue().setLevel(cdkEntity.getParam());
                break;
            case "rogue_coin":
                accountEntity.setExpireTime(accountEntity.getExpireTime().plusDays(2));
                accountEntity.getConfig().getRogue().setCoin(cdkEntity.getParam());
                break;
            default:
                throw new IllegalStateException("Unexpected value: " + cdkEntity.getType());
        }
    }

}
