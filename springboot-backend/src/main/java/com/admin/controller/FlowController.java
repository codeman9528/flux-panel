package com.admin.controller;

import com.admin.common.aop.LogAnnotation;
import com.admin.common.dto.FlowDto;
import com.admin.common.dto.GostConfigDto;
import com.admin.common.lang.R;
import com.admin.common.task.CheckGostConfigAsync;
import com.admin.common.utils.AESCrypto;
import com.admin.common.utils.GostUtil;
import com.admin.entity.*;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.web.bind.annotation.*;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 流量上报控制器
 * 处理节点上报的流量数据，更新用户和隧道的流量统计
 * <p>
 * 主要功能：
 * 1. 接收并处理节点上报的流量数据
 * 2. 更新转发、用户和隧道的流量统计
 * 3. 检查用户总流量限制，超限时暂停所有服务
 * 4. 检查隧道流量限制，超限时暂停对应服务
 * 5. 检查用户到期时间，到期时暂停所有服务
 * 6. 检查隧道权限到期时间，到期时暂停对应服务
 * 7. 检查用户状态，状态不为1时暂停所有服务
 * 8. 检查转发状态，状态不为1时暂停对应转发
 * 9. 检查用户隧道权限状态，状态不为1时暂停对应转发
 * <p>
 * 并发安全解决方案：
 * 1. 使用UpdateWrapper进行数据库层面的原子更新操作，避免读取-修改-写入的竞态条件
 * 2. 使用synchronized锁确保同一用户/隧道的流量更新串行执行
 * 3. 这样可以避免相同用户相同隧道不同转发同时上报时流量统计丢失的问题
 */
@RestController
@RequestMapping("/flow")
@CrossOrigin
@Slf4j
public class FlowController extends BaseController {

    // 常量定义
    private static final String SUCCESS_RESPONSE = "ok";
    private static final String DEFAULT_USER_TUNNEL_ID = "0";
    private static final long BYTES_TO_GB = 1024L * 1024L * 1024L;

    // 用于同步相同用户和隧道的流量更新操作
    private static final ConcurrentHashMap<String, Object> USER_LOCKS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Object> TUNNEL_LOCKS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Object> FORWARD_LOCKS = new ConcurrentHashMap<>();

    // 缓存加密器实例，避免重复创建
    private static final ConcurrentHashMap<String, AESCrypto> CRYPTO_CACHE = new ConcurrentHashMap<>();

    @Resource
    CheckGostConfigAsync checkGostConfigAsync;

    /**
     * 加密消息包装器
     */
    public static class EncryptedMessage {
        private boolean encrypted;
        private String data;
        private Long timestamp;

        // getters and setters
        public boolean isEncrypted() {
            return encrypted;
        }

        public void setEncrypted(boolean encrypted) {
            this.encrypted = encrypted;
        }

        public String getData() {
            return data;
        }

        public void setData(String data) {
            this.data = data;
        }

        public Long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(Long timestamp) {
            this.timestamp = timestamp;
        }
    }

    @PostMapping("/config")
    @LogAnnotation
    public String config(@RequestBody String rawData, String secret,
                         @RequestHeader(value = "X-Node-Secret", required = false) String headerSecret) {
        secret = resolveSecret(secret, headerSecret);
        Node node = nodeService.getOne(new QueryWrapper<Node>().eq("secret", secret));
        if (node == null) return SUCCESS_RESPONSE;

        try {
            // 尝试解密数据（解密失败/未加密会抛异常拒绝，不降级用明文）
            String decryptedData = decryptIfNeeded(rawData, secret);

            // 解析为GostConfigDto
            GostConfigDto gostConfigDto = JSON.parseObject(decryptedData, GostConfigDto.class);
            checkGostConfigAsync.cleanNodeConfigs(node.getId().toString(), gostConfigDto);

            log.info("🔓 节点 {} 配置数据接收成功{}", node.getId(), isEncryptedMessage(rawData) ? "（已解密）" : "");

        } catch (Exception e) {
            log.error("处理节点 {} 配置数据失败: {}", node.getId(), e.getMessage());
        }

        return SUCCESS_RESPONSE;
    }

    @RequestMapping("/test")
    @LogAnnotation
    public String test() {
        return "test";
    }

    /**
     * 处理流量数据上报
     *
     * @param rawData 原始数据（可能是加密的）
     * @param secret  节点密钥
     * @return 处理结果
     */
    @RequestMapping("/upload")
    @LogAnnotation
    public String uploadFlowData(@RequestBody String rawData, String secret,
                                 @RequestHeader(value = "X-Node-Secret", required = false) String headerSecret) {
        secret = resolveSecret(secret, headerSecret);
        // 1. 验证节点权限（取出节点实体，供后续归属校验）
        Node reportNode = nodeService.getOne(new QueryWrapper<Node>().eq("secret", secret));
        if (reportNode == null) {
            return SUCCESS_RESPONSE;
        }

        try {
            // 2. 解密数据（解密失败/未加密直接拒绝，防明文伪造）
            String decryptedData = decryptIfNeeded(rawData, secret);

            // 3. 解析为FlowDto列表
            FlowDto flowDataList = JSONObject.parseObject(decryptedData, FlowDto.class);
            if (Objects.equals(flowDataList.getN(), "web_api")) {
                return SUCCESS_RESPONSE;
            }

            // 4. 拒绝负数流量（负数可把用户已用流量减成负值，永久绕过限额）
            if (flowDataList.getD() == null || flowDataList.getU() == null
                    || flowDataList.getD() < 0 || flowDataList.getU() < 0) {
                log.warn("节点 {} 上报非法流量数值，已拒绝: {}", reportNode.getId(), flowDataList);
                return SUCCESS_RESPONSE;
            }

            // 记录日志
            log.info("节点上报流量数据{}", flowDataList);
            // 5. 处理流量数据
            return processFlowData(flowDataList, reportNode);
        } catch (Exception e) {
            log.warn("节点 {} 流量上报处理失败，已忽略: {}", reportNode.getId(), e.getMessage());
            return SUCCESS_RESPONSE;
        }
    }

    /**
     * secret 优先取 Header（新版gost），兼容老版本的 query 参数
     */
    private String resolveSecret(String querySecret, String headerSecret) {
        if (headerSecret != null && !headerSecret.isEmpty()) {
            return headerSecret;
        }
        return querySecret;
    }

    /**
     * 检测消息是否为加密格式
     */
    private boolean isEncryptedMessage(String data) {
        try {
            JSONObject json = JSON.parseObject(data);
            return json.getBooleanValue("encrypted");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 根据需要解密数据
     */
    private String decryptIfNeeded(String rawData, String secret) {
        if (rawData == null || rawData.trim().isEmpty()) {
            throw new IllegalArgumentException("数据不能为空");
        }

        // 强制要求加密：流量数据决定计费，若接受明文则持有secret的攻击者可直接伪造。
        // 解析失败、未加密、解密失败一律抛异常拒绝，不再降级使用明文。
        EncryptedMessage encryptedMessage = JSON.parseObject(rawData, EncryptedMessage.class);
        if (encryptedMessage == null || !encryptedMessage.isEncrypted() || encryptedMessage.getData() == null) {
            throw new IllegalArgumentException("仅接受加密上报，明文数据已拒绝");
        }

        AESCrypto crypto = getOrCreateCrypto(secret);
        if (crypto == null) {
            throw new IllegalStateException("无法创建解密器");
        }

        return crypto.decryptString(encryptedMessage.getData());
    }

    /**
     * 获取或创建加密器实例
     */
    private AESCrypto getOrCreateCrypto(String secret) {
        return CRYPTO_CACHE.computeIfAbsent(secret, AESCrypto::create);
    }

    /**
     * 处理流量数据的核心逻辑
     */
    private String processFlowData(FlowDto flowDataList, Node reportNode) {
        String[] serviceIds = parseServiceName(flowDataList.getN());
        // 畸形服务名直接丢弃，避免数组越界500
        if (serviceIds.length < 3) {
            log.warn("节点 {} 上报非法服务名，已忽略: {}", reportNode.getId(), flowDataList.getN());
            return SUCCESS_RESPONSE;
        }
        String forwardId = serviceIds[0];
        String userId = serviceIds[1];
        String userTunnelId = serviceIds[2];

        Forward forward = forwardService.getById(forwardId);

        // 转发已不存在：残留服务的上报不再按名字里的ID继续扣费，直接丢弃等待对账清理
        if (forward == null) {
            log.warn("节点 {} 上报的转发 {} 不存在，已忽略（残留服务待对账清理）", reportNode.getId(), forwardId);
            return SUCCESS_RESPONSE;
        }

        // 节点归属校验：上报节点必须是该转发所属隧道的入口或出口节点，
        // 防止持有任一节点secret就能给任意用户刷流量/嫁祸计费
        Tunnel ownerTunnel = tunnelService.getById(forward.getTunnelId());
        if (ownerTunnel == null
                || (!Objects.equals(ownerTunnel.getInNodeId(), reportNode.getId())
                    && !Objects.equals(ownerTunnel.getOutNodeId(), reportNode.getId()))) {
            log.warn("节点 {} 上报了不属于它的转发 {}（隧道 {}），已拒绝",
                    reportNode.getId(), forwardId, forward.getTunnelId());
            return SUCCESS_RESPONSE;
        }

        // 防伪造：服务名里的 userId 必须与转发记录的真实归属一致
        if (!Objects.equals(String.valueOf(forward.getUserId()), userId)) {
            log.warn("节点 {} 上报的服务名用户 {} 与转发 {} 实际归属 {} 不符，已拒绝",
                    reportNode.getId(), userId, forwardId, forward.getUserId());
            return SUCCESS_RESPONSE;
        }

        // 获取流量计费类型
        int flowType = getFlowType(forward);

        //  处理流量倍率及单双向计算
        FlowDto flowStats = filterFlowData(flowDataList, forward, flowType);

        // 先更新所有流量统计 - 确保流量数据的一致性
        updateForwardFlow(forwardId, flowStats);
        updateUserFlow(userId, flowStats);
        updateUserTunnelFlow(userTunnelId, flowStats);

        // 7. 检查和服务暂停操作
        if (!Objects.equals(userTunnelId, DEFAULT_USER_TUNNEL_ID)) { // 非管理员的转发需要检测流量限制
            checkUserRelatedLimits(userId);
            checkUserTunnelRelatedLimits(userTunnelId, userId);
        }

        return SUCCESS_RESPONSE;
    }

    private void checkUserRelatedLimits(String userId) {

        // 重新查询用户以获取最新的流量数据
        User updatedUser = userService.getById(userId);
        if (updatedUser == null) return;

        // 检查用户总流量限制
        long userFlowLimit = updatedUser.getFlow() * BYTES_TO_GB;
        long userCurrentFlow = updatedUser.getInFlow() + updatedUser.getOutFlow();
        if (userFlowLimit < userCurrentFlow) {
            pauseAllUserServices(userId);
            return;
        }

        // 检查用户到期时间
        if (updatedUser.getExpTime() != null && updatedUser.getExpTime() <= new Date().getTime()) {
            pauseAllUserServices(userId);
            return;
        }

        // 检查用户状态
        if (updatedUser.getStatus() != 1) {
            pauseAllUserServices(userId);
        }
    }

    public void pauseAllUserServices(String userId) {
        List<Forward> forwardList = forwardService.list(new QueryWrapper<Forward>().eq("user_id", userId));
        pauseService(forwardList);
    }

    public void checkUserTunnelRelatedLimits(String userTunnelId, String userId) {

        UserTunnel userTunnel = userTunnelService.getById(userTunnelId);
        if (userTunnel == null) return;
        long flow = userTunnel.getInFlow() + userTunnel.getOutFlow();
        if (flow >= userTunnel.getFlow() *  BYTES_TO_GB) {
            pauseSpecificForward(userTunnel.getTunnelId(), userId);
            return;
        }

        if (userTunnel.getExpTime() != null && userTunnel.getExpTime() <= System.currentTimeMillis()) {
            pauseSpecificForward(userTunnel.getTunnelId(), userId);
            return;
        }

        if (userTunnel.getStatus() != 1) {
            pauseSpecificForward(userTunnel.getTunnelId(), userId);
        }


    }

    private void pauseSpecificForward(Integer tunnelId, String userId) {
        List<Forward> forwardList = forwardService.list(new QueryWrapper<Forward>().eq("tunnel_id", tunnelId).eq("user_id", userId));
        pauseService(forwardList);
    }

    public void pauseService(List<Forward> forwardList) {
        for (Forward forward : forwardList) {
            Tunnel tunnel = tunnelService.getById(forward.getTunnelId());
            if (tunnel != null){
                // 必须用每条转发自己的服务名下发暂停：此前所有转发都用"触发上报那条"的名字，
                // gost侧对不上名字直接not found，其它转发只是DB置0、节点上仍在跑
                String serviceName = buildForwardServiceName(forward);
                GostUtil.PauseService(tunnel.getInNodeId(), serviceName);
                if (tunnel.getType() == 2){
                    GostUtil.PauseRemoteService(tunnel.getOutNodeId(), serviceName);
                }
            }
            forward.setStatus(0);
            forwardService.updateById(forward);
        }
    }

    /**
     * 重建某条转发在gost侧的服务名：forwardId_userId_userTunnelId（无用户隧道记录时为0）
     */
    private String buildForwardServiceName(Forward forward) {
        UserTunnel userTunnel = userTunnelService.getOne(new QueryWrapper<UserTunnel>()
                .eq("user_id", forward.getUserId())
                .eq("tunnel_id", forward.getTunnelId()), false);
        String utid = userTunnel != null ? String.valueOf(userTunnel.getId()) : DEFAULT_USER_TUNNEL_ID;
        return forward.getId() + "_" + forward.getUserId() + "_" + utid;
    }

    private FlowDto filterFlowData(FlowDto flowDto, Forward forward, int flowType) {
        if (forward != null) {
            Tunnel tunnel = tunnelService.getById(forward.getTunnelId());
            if (tunnel != null) {
                BigDecimal trafficRatio = tunnel.getTrafficRatio();

                BigDecimal originalD = BigDecimal.valueOf(flowDto.getD());
                BigDecimal originalU = BigDecimal.valueOf(flowDto.getU());

                BigDecimal newD = originalD.multiply(trafficRatio);
                BigDecimal newU = originalU.multiply(trafficRatio);

                flowDto.setD(newD.longValue() * flowType);
                flowDto.setU(newU.longValue() * flowType);
            }
        }
        return flowDto;
    }

    private int getFlowType(Forward forward) {
        int defaultFlowType = 2;
        if (forward == null) return defaultFlowType;
        Tunnel tunnel = tunnelService.getById(forward.getTunnelId());
        if (tunnel == null) return defaultFlowType;
        return tunnel.getFlow();
    }

    private void updateForwardFlow(String forwardId, FlowDto flowStats) {
        // 对相同转发的流量更新进行同步，避免并发覆盖
        synchronized (getForwardLock(forwardId)) {
            UpdateWrapper<Forward> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq("id", forwardId);
            updateWrapper.setSql("in_flow = in_flow + " + flowStats.getD());
            updateWrapper.setSql("out_flow = out_flow + " + flowStats.getU());

            forwardService.update(null, updateWrapper);
        }
    }

    private void updateUserFlow(String userId, FlowDto flowStats) {
        // 对相同用户的流量更新进行同步，避免并发覆盖
        synchronized (getUserLock(userId)) {
            UpdateWrapper<User> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq("id", userId);

            updateWrapper.setSql("in_flow = in_flow + " + flowStats.getD());
            updateWrapper.setSql("out_flow = out_flow + " + flowStats.getU());

            userService.update(null, updateWrapper);
        }
    }

    private void updateUserTunnelFlow(String userTunnelId, FlowDto flowStats) {
        if (Objects.equals(userTunnelId, DEFAULT_USER_TUNNEL_ID)) {
            return; // 默认隧道不需要更新，返回成功
        }

        // 对相同用户隧道的流量更新进行同步，避免并发覆盖
        synchronized (getTunnelLock(userTunnelId)) {
            UpdateWrapper<UserTunnel> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq("id", userTunnelId);
            updateWrapper.setSql("in_flow = in_flow + " + flowStats.getD());
            updateWrapper.setSql("out_flow = out_flow + " + flowStats.getU());
            userTunnelService.update(null, updateWrapper);
        }
    }

    private Object getUserLock(String userId) {
        return USER_LOCKS.computeIfAbsent(userId, k -> new Object());
    }

    private Object getTunnelLock(String userTunnelId) {
        return TUNNEL_LOCKS.computeIfAbsent(userTunnelId, k -> new Object());
    }

    private Object getForwardLock(String forwardId) {
        return FORWARD_LOCKS.computeIfAbsent(forwardId, k -> new Object());
    }

    private boolean isValidNode(String secret) {
        int nodeCount = nodeService.count(new QueryWrapper<Node>().eq("secret", secret));
        return nodeCount > 0;
    }

    private String[] parseServiceName(String serviceName) {
        return serviceName.split("_");
    }

    private String buildServiceName(String forwardId, String userId, String userTunnelId) {
        return forwardId + "_" + userId + "_" + userTunnelId;
    }
}
