package com.spzx.common.redis.cache;

import com.alibaba.fastjson2.JSON;
import com.spzx.common.redis.annotation.GuiguCache;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 缓存切面：AOP环绕通知实现声明式缓存
 *
 * 处理缓存三大问题：
 * 1.缓存穿透：查询不存在的数据也缓存空值（短TTL）
 * 2.缓存击穿：分布式锁保证并发请求只有一个去查库，拿到锁后二次检查缓存
 * 3.缓存雪崩：TTL加随机偏移，避免同一时刻大量key同时过期
 *
 * 类型还原设计（关键）：
 * 缓存值以JSON字符串存储（StringRedisTemplate），
 * 读取时按方法返回类型（含泛型，如 Map&lt;String, Long&gt;）反序列化，
 * 避免反序列化成 JSONObject/Integer 导致调用方 ClassCastException，
 * 同时不依赖 FastJson autotype 白名单，安全性更好。
 */
@Slf4j
@Component
@Aspect
public class GuiguCacheAspect {

    /** 空值标记，防缓存穿透 */
    private static final String NULL_MARKER = "__NULL__";

    /** 空值命中哨兵：区分「缓存了空值，直接返回null」和「缓存未命中，需要查库」两种情况 */
    private static final Object NULL_SENTINEL = new Object();

    /** 分布式锁TTL（秒），必须大于业务方法执行时间 */
    private static final long LOCK_TTL_SECONDS = 30;

    /** 获取锁重试次数 */
    private static final int LOCK_RETRY_COUNT = 3;

    /** 每次重试间隔（毫秒） */
    private static final long LOCK_RETRY_INTERVAL_MS = 100;

    /** TTL随机偏移比例，防雪崩：实际TTL = expire * (1 + random(-0.1, 0.1)) */
    private static final double TTL_JITTER_RATIO = 0.1;

    @Autowired
    StringRedisTemplate stringRedisTemplate;

    /** Lua脚本：原子释放锁（比对value防止误删他人的锁） */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setScriptText(
                "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                "    return redis.call('del', KEYS[1]) " +
                "else " +
                "    return 0 " +
                "end"
        );
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Around(value = "@annotation(guiguCache)")
    public Object guiguCache(ProceedingJoinPoint joinPoint, GuiguCache guiguCache) throws Throwable {
        // 1.构建缓存key：prefix + 类名:方法名:参数
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String className = method.getDeclaringClass().getSimpleName();
        String methodName = method.getName();
        String argsStr = Arrays.toString(joinPoint.getArgs());
        String cacheKey = guiguCache.prefix() + className + ":" + methodName + ":" + argsStr;

        // 2.第一次查缓存，命中则按方法返回类型（含泛型）还原对象
        Object cachedValue = getFromCache(cacheKey, method);
        if (cachedValue != null) {
            // 命中空值哨兵说明数据库无该数据，直接返回null，不再查库（防穿透关键）
            return cachedValue == NULL_SENTINEL ? null : cachedValue;
        }

        // 3.缓存未命中，尝试获取分布式锁（防击穿）
        String lockKey = cacheKey + ":lock";
        String lockValue = UUID.randomUUID().toString();
        boolean locked = false;

        for (int i = 0; i < LOCK_RETRY_COUNT; i++) {
            Boolean result = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, lockValue, LOCK_TTL_SECONDS, TimeUnit.SECONDS);
            if (Boolean.TRUE.equals(result)) {
                locked = true;
                break;
            }
            // 未拿到锁，等待后双检：其他线程可能已经填充了缓存
            Thread.sleep(LOCK_RETRY_INTERVAL_MS);
            cachedValue = getFromCache(cacheKey, method);
            if (cachedValue != null) {
                log.debug("等待锁期间缓存被填充，直接返回: {}", cacheKey);
                return cachedValue == NULL_SENTINEL ? null : cachedValue;
            }
        }

        try {
            // 4.拿到锁后再次检查缓存（前一个持锁线程可能刚填充完），避免重复查库
            if (locked) {
                cachedValue = getFromCache(cacheKey, method);
                if (cachedValue != null) {
                    log.debug("拿到锁后二次检查命中缓存: {}", cacheKey);
                    return cachedValue == NULL_SENTINEL ? null : cachedValue;
                }
            }

            // 5.执行目标方法（查数据库）
            Object dbResult = joinPoint.proceed();

            // 6.写入缓存
            if (dbResult == null) {
                // 空值短TTL缓存，防穿透
                stringRedisTemplate.opsForValue().set(cacheKey, NULL_MARKER, guiguCache.nullExpire(), TimeUnit.SECONDS);
                log.debug("数据库无数据，缓存空值防穿透: {}", cacheKey);
            } else {
                // 正常TTL + 随机偏移，防雪崩
                long jitteredExpire = calculateJitteredTTL(guiguCache.expire());
                stringRedisTemplate.opsForValue().set(cacheKey, JSON.toJSONString(dbResult), jitteredExpire, TimeUnit.SECONDS);
                log.debug("数据库数据写入缓存: {}, TTL={}s", cacheKey, jitteredExpire);
            }
            return dbResult;
        } finally {
            // 7.释放锁（Lua脚本原子操作）
            if (locked) {
                releaseLock(lockKey, lockValue);
            }
        }
    }

    /**
     * 从缓存读取并按方法返回类型还原
     * 返回null表示缓存未命中；
     * 返回NULL_SENTINEL表示命中空值缓存（数据库确实无该数据，调用方应直接返回null）；
     * 其他情况返回按方法返回类型（含泛型）反序列化后的对象
     */
    private Object getFromCache(String cacheKey, Method method) {
        String cachedJson = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cachedJson == null) {
            return null;
        }
        if (NULL_MARKER.equals(cachedJson)) {
            log.debug("缓存命中（空值防穿透）: {}", cacheKey);
            return NULL_SENTINEL;
        }
        // 按方法返回类型（含泛型）反序列化，保证 Map<String, Long> 等泛型的类型正确
        return JSON.parseObject(cachedJson, method.getGenericReturnType());
    }

    /**
     * 计算带随机偏移的TTL，避免大量key同时过期造成缓存雪崩
     * 实际TTL = expire * (1 + random(-0.1, +0.1))
     */
    private long calculateJitteredTTL(long expireSeconds) {
        double jitter = ThreadLocalRandom.current().nextDouble(-TTL_JITTER_RATIO, TTL_JITTER_RATIO);
        return Math.max(1, (long) (expireSeconds * (1 + jitter)));
    }

    /**
     * 原子释放分布式锁
     * 先比对lockValue再删除，防止误删其他线程持有的锁
     */
    private void releaseLock(String lockKey, String lockValue) {
        try {
            Long result = stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(lockKey), lockValue);
            if (result != null && result == 1) {
                log.debug("释放锁成功: {}", lockKey);
            } else {
                log.warn("释放锁失败（锁已被他人持有或已过期）: {}", lockKey);
            }
        } catch (Exception e) {
            log.error("释放锁异常: {}", lockKey, e);
        }
    }
}
