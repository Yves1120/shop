package com.spzx.common.redis.cache;

import com.spzx.common.redis.annotation.GuiguCache;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缓存切面单元测试：验证缓存三大问题的实际处理逻辑
 *
 * 1.缓存命中：直接返回，不查库
 * 2.空值防穿透：命中空值标记返回null，不查库
 * 3.缓存未命中+抢锁成功：查库并写入缓存（TTL带随机抖动）
 * 4.缓存未命中+未抢到锁：双检等待期间缓存被填充则直接返回
 * 5.泛型类型还原：Map&lt;String,Long&gt;反序列化后类型正确
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GuiguCacheAspectTest {

    /** 模拟被缓存注解标注的业务方法（返回泛型Map，验证类型还原） */
    static class CacheTarget {
        @GuiguCache(prefix = "product:spec:", expire = 3600)
        public Map<String, Long> getSkuSpecValue(Long id) {
            return null;
        }
    }

    private static final String PREFIX = "product:spec:";
    private static final String CACHE_KEY = PREFIX + "CacheTarget:getSkuSpecValue:[5]";
    private static final String LOCK_KEY = CACHE_KEY + ":lock";

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ProceedingJoinPoint joinPoint;

    @Mock
    private MethodSignature signature;

    @InjectMocks
    private GuiguCacheAspect aspect;

    private GuiguCache guiguCache;

    @BeforeEach
    void setUp() throws Exception {
        ReflectionTestUtils.setField(aspect, "stringRedisTemplate", stringRedisTemplate);
        doReturn(valueOperations).when(stringRedisTemplate).opsForValue();

        // 注解参数：使用动态代理获取真实注解实例
        guiguCache = CacheTarget.class.getMethod("getSkuSpecValue", Long.class)
                .getAnnotation(GuiguCache.class);

        // 模拟切点解析出的方法签名与参数
        Method method = CacheTarget.class.getMethod("getSkuSpecValue", Long.class);
        when(signature.getMethod()).thenReturn(method);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(new Object[]{5L});
    }

    @Test
    void cacheHit_returnsValueWithoutDb_andGenericTypeRestored() throws Throwable {
        // 缓存中已有数据（模拟之前写入的JSON）
        when(valueOperations.get(CACHE_KEY)).thenReturn("{\"红色-XL\":100,\"蓝色-M\":200}");

        Object result = aspect.guiguCache(joinPoint, guiguCache);

        // 泛型类型还原：反序列化后必须还是Map<String,Long>，而非JSONObject
        assertThat(result).isInstanceOf(Map.class);
        Map<String, Long> map = (Map<String, Long>) result;
        assertThat(map.get("红色-XL")).isEqualTo(100L);
        assertThat(map.get("蓝色-M")).isEqualTo(200L);
        // 命中缓存绝不查库
        verify(joinPoint, never()).proceed();
    }

    @Test
    void nullMarkerHit_returnsNullWithoutDb_防穿透() throws Throwable {
        // 命中空值标记（数据库确实无该数据）
        when(valueOperations.get(CACHE_KEY)).thenReturn("__NULL__");

        Object result = aspect.guiguCache(joinPoint, guiguCache);

        // 返回null且不查库：穿透防护的关键断言
        assertThat(result).isNull();
        verify(joinPoint, never()).proceed();
    }

    @Test
    void cacheMiss_acquiresLock_queriesDb_thenCachesResult() throws Throwable {
        // 缓存未命中 → 抢锁成功
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);
        when(valueOperations.setIfAbsent(eq(LOCK_KEY), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);

        Map<String, Long> dbResult = Map.of("红色-XL", 100L);
        when(joinPoint.proceed()).thenReturn(dbResult);

        Object result = aspect.guiguCache(joinPoint, guiguCache);

        // 返回数据库结果
        assertThat(result).isSameAs(dbResult);
        // 查库一次
        verify(joinPoint).proceed();
        // 写入缓存：key正确，值为JSON，TTL带随机抖动（3600 * (1±10%) → 3240~3960之间）
        verify(valueOperations).set(eq(CACHE_KEY), anyString(), anyLong(), any(TimeUnit.class));
        // 释放了分布式锁（locked=true才会调用Lua脚本）
        verify(stringRedisTemplate).execute(any(), eq(java.util.Collections.singletonList(LOCK_KEY)), anyString());
    }

    @Test
    void dbReturnsNull_cachesNullMarkerWithShortTtl() throws Throwable {
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);
        when(valueOperations.setIfAbsent(eq(LOCK_KEY), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
        // 数据库也无该数据
        when(joinPoint.proceed()).thenReturn(null);

        Object result = aspect.guiguCache(joinPoint, guiguCache);

        assertThat(result).isNull();
        // 写入空值标记，TTL为注解配置的nullExpire（默认30秒）
        verify(valueOperations).set(eq(CACHE_KEY), eq("__NULL__"), eq(30L), eq(TimeUnit.SECONDS));
    }

    @Test
    void lockUnavailable_cacheFilledDuringRetry_returnsCachedValue() throws Throwable {
        // 第一次查缓存未命中 → 抢锁失败 → 重试等待期间其他线程填充了缓存
        when(valueOperations.get(CACHE_KEY)).thenReturn(null, "{\"红色-XL\":100}");
        when(valueOperations.setIfAbsent(eq(LOCK_KEY), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(false);

        Object result = aspect.guiguCache(joinPoint, guiguCache);

        // 返回其他线程填充的缓存，自己不查库（击穿防护的关键断言）
        assertThat(result).isInstanceOf(Map.class);
        verify(joinPoint, never()).proceed();
        // 未拿到锁不释放锁
        verify(stringRedisTemplate, never()).execute(any(), any(java.util.List.class), anyString());
    }
}
