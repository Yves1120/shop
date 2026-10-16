package com.spzx.common.redis.annotation;

import java.lang.annotation.*;

/**
 * 声明式缓存注解
 * 作用：
 * 1.优先从Redis缓存获取数据，提高查询效率
 * 2.缓存未命中时，通过分布式锁防止缓存击穿（并发请求只放一个去查库）
 * 3.查询结果为null也会缓存（短TTL），解决缓存穿透问题
 * 4.缓存TTL加随机偏移，缓解缓存雪崩
 * 5.通过Lua脚本原子释放锁，防止误删他人锁
 */
@Target(value = ElementType.METHOD)
@Retention(value = RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface GuiguCache {

    /**
     * 缓存key前缀，建议按业务命名，如 "product:sku:"
     */
    String prefix() default "cache:";

    /**
     * 缓存过期时间（秒），默认24小时
     */
    long expire() default 86400;

    /**
     * 空值缓存过期时间（秒），用于防缓存穿透，默认30秒
     */
    long nullExpire() default 30;
}
