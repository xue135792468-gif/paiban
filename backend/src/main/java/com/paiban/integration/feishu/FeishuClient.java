package com.paiban.integration.feishu;

import java.util.List;

/** Thin port over the Feishu open platform so it can be faked in tests. */
public interface FeishuClient {

    /** Exchanges an H5免登 code for the Feishu user it belongs to. */
    FeishuUser exchangeAuthCode(String code);

    List<FeishuDepartment> listDepartments();

    List<FeishuUser> listUsers();

    boolean sendMessage(FeishuMessage message);
}
