package com.caradvice.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "rate_limit_log", indexes = {
        @Index(name = "idx_rl_time", columnList = "request_time")
})
public class RateLimitLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 45)
    private String ip;

    @Column(name = "endpoint_type", nullable = false, length = 20)
    private String endpointType;

    @Column(name = "request_time", nullable = false)
    private LocalDateTime requestTime;

    public RateLimitLog() {}

    public RateLimitLog(String ip, String endpointType, LocalDateTime requestTime) {
        this.ip = ip;
        this.endpointType = endpointType;
        this.requestTime = requestTime;
    }

    public String getIp() { return ip; }
    public String getEndpointType() { return endpointType; }
    public LocalDateTime getRequestTime() { return requestTime; }
}
