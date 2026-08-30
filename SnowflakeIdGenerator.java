package com.mahii.urlshortener.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Distributed unique ID generator based on Twitter's Snowflake algorithm.
 * 
 * Structure (64-bit long):
 * - 1 bit: Sign bit (always 0)
 * - 41 bits: Timestamp (milliseconds since epoch)
 * - 5 bits: Data Center ID (0-31)
 * - 5 bits: Machine ID (0-31)
 * - 12 bits: Sequence number (0-4095)
 * 
 * This allows generating 4096 unique IDs per millisecond per machine.
 */
@Slf4j
@Component
public class SnowflakeIdGenerator {
    
    // Bit lengths
    private static final int TIMESTAMP_BITS = 41;
    private static final int DATACENTER_ID_BITS = 5;
    private static final int MACHINE_ID_BITS = 5;
    private static final int SEQUENCE_BITS = 12;
    
    // Max values
    private static final long MAX_DATACENTER_ID = (1L << DATACENTER_ID_BITS) - 1; // 31
    private static final long MAX_MACHINE_ID = (1L << MACHINE_ID_BITS) - 1; // 31
    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1; // 4095
    
    // Bit shift amounts
    private static final int TIMESTAMP_LEFT_SHIFT = DATACENTER_ID_BITS + MACHINE_ID_BITS + SEQUENCE_BITS;
    private static final int DATACENTER_ID_LEFT_SHIFT = MACHINE_ID_BITS + SEQUENCE_BITS;
    private static final int MACHINE_ID_LEFT_SHIFT = SEQUENCE_BITS;
    
    // Custom epoch: Jan 1, 2020 (in milliseconds)
    private static final long CUSTOM_EPOCH = 1577836800000L;
    
    private final long datacenterId;
    private final long machineId;
    private long lastTimestamp = -1L;
    private long sequence = 0L;
    
    public SnowflakeIdGenerator(
            @Value("${snowflake.datacenter-id:1}") long datacenterId,
            @Value("${snowflake.machine-id:1}") long machineId) {
        
        if (datacenterId < 0 || datacenterId > MAX_DATACENTER_ID) {
            throw new IllegalArgumentException("Datacenter ID must be between 0 and " + MAX_DATACENTER_ID);
        }
        if (machineId < 0 || machineId > MAX_MACHINE_ID) {
            throw new IllegalArgumentException("Machine ID must be between 0 and " + MAX_MACHINE_ID);
        }
        
        this.datacenterId = datacenterId;
        this.machineId = machineId;
        
        log.info("SnowflakeIdGenerator initialized with datacenter={}, machine={}", 
                datacenterId, machineId);
    }
    
    /**
     * Generate next unique ID.
     * Thread-safe implementation using synchronized block.
     */
    public synchronized long nextId() {
        long currentTimestamp = System.currentTimeMillis();
        
        // Clock went backwards - this should rarely happen in practice
        if (currentTimestamp < lastTimestamp) {
            throw new IllegalStateException(
                    String.format("Clock moved backwards. Refusing to generate id for %d ms",
                            lastTimestamp - currentTimestamp));
        }
        
        if (currentTimestamp == lastTimestamp) {
            // Increment sequence within the same millisecond
            sequence = (sequence + 1) & MAX_SEQUENCE;
            
            if (sequence == 0) {
                // Sequence overflow - wait for next millisecond
                currentTimestamp = waitUntilNextMillis(lastTimestamp);
            }
        } else {
            // New millisecond - reset sequence
            sequence = 0L;
        }
        
        lastTimestamp = currentTimestamp;
        
        // Compose ID: (timestamp << 22) | (datacenter << 17) | (machine << 12) | sequence
        long timestamp = currentTimestamp - CUSTOM_EPOCH;
        return (timestamp << TIMESTAMP_LEFT_SHIFT)
                | (datacenterId << DATACENTER_ID_LEFT_SHIFT)
                | (machineId << MACHINE_ID_LEFT_SHIFT)
                | sequence;
    }
    
    /**
     * Wait until the next millisecond.
     */
    private long waitUntilNextMillis(long lastTimestamp) {
        long timestamp = System.currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }
    
    /**
     * Parse a Snowflake ID into its components (useful for debugging/analytics).
     */
    public IdComponents parseId(long id) {
        long timestamp = (id >> TIMESTAMP_LEFT_SHIFT) + CUSTOM_EPOCH;
        long datacenterId = (id >> DATACENTER_ID_LEFT_SHIFT) & MAX_DATACENTER_ID;
        long machineId = (id >> MACHINE_ID_LEFT_SHIFT) & MAX_MACHINE_ID;
        long sequence = id & MAX_SEQUENCE;
        
        return new IdComponents(timestamp, datacenterId, machineId, sequence);
    }
    
    public record IdComponents(long timestamp, long datacenterId, long machineId, long sequence) {}
}
