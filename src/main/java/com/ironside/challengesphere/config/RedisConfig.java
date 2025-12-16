package com.ironside.challengesphere.config;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisClientConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Redis Configuration using Jedis client
 * Configures connection to Google Cloud Memorystore Redis
 */
@Configuration
public class RedisConfig {

    private static final Logger logger = LogManager.getLogger(RedisConfig.class);

    @Value("${spring.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.redis.port:6379}")
    private int redisPort;

    @Value("${spring.redis.password:}")
    private String redisPassword;

    @Value("${spring.redis.timeout:60000}")
    private long timeout;

    @Value("${spring.redis.database:0}")
    private int database;

    @Value("${spring.redis.ssl:false}")
    private boolean ssl;

    @Value("${spring.redis.jedis.pool.max-active:8}")
    private int maxActive;

    @Value("${spring.redis.jedis.pool.max-idle:8}")
    private int maxIdle;

    @Value("${spring.redis.jedis.pool.min-idle:0}")
    private int minIdle;

    @Value("${spring.redis.jedis.pool.max-wait:-1}")
    private long maxWait;

    /**
     * Configure Jedis connection pool
     */
    @Bean
    public GenericObjectPoolConfig<Object> jedisPoolConfig() {
        logger.info("Configuring Jedis connection pool");

        GenericObjectPoolConfig<Object> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(maxActive);
        poolConfig.setMaxIdle(maxIdle);
        poolConfig.setMinIdle(minIdle);
        poolConfig.setMaxWait(Duration.ofMillis(maxWait));
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestOnReturn(true);
        poolConfig.setTestWhileIdle(true);
        poolConfig.setMinEvictableIdleDuration(Duration.ofSeconds(60));
        poolConfig.setTimeBetweenEvictionRuns(Duration.ofSeconds(30));
        poolConfig.setNumTestsPerEvictionRun(3);
        poolConfig.setBlockWhenExhausted(true);

        logger.info("Jedis pool configured: maxActive={}, maxIdle={}, minIdle={}", maxActive, maxIdle, minIdle);
        return poolConfig;
    }

    @Bean
    public RedisConnectionFactory redisConnectionFactory(GenericObjectPoolConfig<Object> jedisPoolConfig) {
        logger.info("Initializing Redis connection factory with host: {}, port: {}", redisHost, redisPort);

        // Create standalone configuration
        RedisStandaloneConfiguration redisStandaloneConfiguration =
            new RedisStandaloneConfiguration();
        redisStandaloneConfiguration.setHostName(redisHost);
        redisStandaloneConfiguration.setPort(redisPort);
        redisStandaloneConfiguration.setDatabase(database);

        // Set password if provided
        if (redisPassword != null && !redisPassword.isEmpty()) {
            redisStandaloneConfiguration.setPassword(redisPassword);
            logger.info("Redis password configured");
        }

        // Configure Jedis client with connection pool
        JedisClientConfiguration.JedisClientConfigurationBuilder clientConfigBuilder = JedisClientConfiguration.builder();

        if (ssl) {
            clientConfigBuilder.useSsl();
            logger.info("SSL enabled for Redis connection");
        }

        JedisClientConfiguration jedisClientConfig = clientConfigBuilder
            .usePooling()
            .poolConfig(jedisPoolConfig)
            .build();

        // Create and configure the Jedis connection factory
        JedisConnectionFactory factory = new JedisConnectionFactory(
            redisStandaloneConfiguration,
            jedisClientConfig
        );

        logger.info("Redis connection factory initialized successfully");
        return factory;
    }

    /**
     * Configure RedisTemplate with JSON serialization
     * Use this for manual Redis operations
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        logger.info("Configuring RedisTemplate with GenericJackson2Json serialization");

        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Create GenericJackson2JsonRedisSerializer for Object serialization
        GenericJackson2JsonRedisSerializer jackson2JsonRedisSerializer =
            new GenericJackson2JsonRedisSerializer();

        // String serializer for keys
        StringRedisSerializer stringRedisSerializer = new StringRedisSerializer();

        // Set key serialization
        template.setKeySerializer(stringRedisSerializer);
        template.setHashKeySerializer(stringRedisSerializer);

        // Set value serialization
        template.setValueSerializer(jackson2JsonRedisSerializer);
        template.setHashValueSerializer(jackson2JsonRedisSerializer);

        template.afterPropertiesSet();
        logger.info("RedisTemplate configured successfully");
        return template;
    }

    /**
     * Configure Cache Manager with Redis backend
     * Use this with Spring @Cacheable annotations
     */
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        logger.info("Configuring RedisCacheManager");

        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofHours(1))
                .disableCachingNullValues()
                .serializeKeysWith(
                    RedisSerializationContext.SerializationPair.fromSerializer(
                        new StringRedisSerializer()
                    )
                )
                .serializeValuesWith(
                    RedisSerializationContext.SerializationPair.fromSerializer(
                        new GenericJackson2JsonRedisSerializer()
                    )
                );

        logger.info("RedisCacheManager configured with 1 hour TTL");
        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config)
                .build();
    }
}

