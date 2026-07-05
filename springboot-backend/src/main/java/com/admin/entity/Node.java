package com.admin.entity;

import java.io.Serializable;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 * 
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class Node extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private String name;

    private String secret;

    private String ip;

    private String serverIp;

    private String version;

    private Integer portSta;

    private Integer portEnd;

    private Integer http;

    private Integer tls;

    private Integer socks;

    /**
     * 当前归属机器的安装令牌（换机时新机令牌更大→接管，旧机重连令牌更小→被拒，实现稳定接管）
     */
    private Long installId;

}
