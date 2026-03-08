package com.example.aitmk.service;

import com.example.aitmk.model.domain.AssignmentRecord;
import com.example.aitmk.model.domain.CrmAgentAccount;
import com.example.aitmk.model.domain.CrmChatRecord;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface CrmOpenApiService {

    Optional<CrmAgentAccount> verifyLogin(String username, String password);

    /** 查询当前坐席是否已有在线状态记录（返回在线记录rowId）。 */
    Optional<String> findOnlineLoginRecordRowId(String agentAccountRowId);

    /** 写登录记录，返回记录 rowId（用于后续更新离线状态）。 */
    Optional<String> addAgentLoginRecord(String agentAccountRowId, String status);

    /** 更新已有登录记录状态（在线/离线）。 */
    boolean updateAgentLoginStatus(String loginRecordRowId, String status);

    boolean addAssignmentRecord(String customerPhone, String agentAccountRowId, String serviceStatus);

    boolean addChatRecord(String businessAccountId,
                          String customerPhone,
                          String agentAccountRowId,
                          String sender,
                          String message);

    List<AssignmentRecord> listAssignments();

    List<CrmChatRecord> listChatRecords();

    Set<String> listOnlineAgents();
}
