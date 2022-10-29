package moe.dazecake.inquisition.service;

import moe.dazecake.inquisition.entity.AccountEntity;

public interface CDKService {

    int activateCDK(Long id, String cdk);

    int createUserByCDK(AccountEntity accountEntity, String cdk);
}
