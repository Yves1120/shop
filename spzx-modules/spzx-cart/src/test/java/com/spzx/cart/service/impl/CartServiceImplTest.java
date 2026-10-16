package com.spzx.cart.service.impl;

import com.alibaba.fastjson2.JSON;
import com.spzx.cart.api.domain.CartInfo;
import com.spzx.common.core.constant.SecurityConstants;
import com.spzx.common.core.context.SecurityContextHolder;
import com.spzx.common.core.domain.R;
import com.spzx.product.api.RemoteProductService;
import com.spzx.product.api.domain.ProductSku;
import com.spzx.product.api.domain.vo.SkuPrice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 购物车服务单元测试：验证Redis Hash结构下的核心购物车逻辑
 *
 * 1.加购已有商品：数量累加，不重复调用商品服务
 * 2.加购新商品：从商品服务获取信息后写入Hash
 * 3.查询购物车：用商品服务最新价格刷新本地缓存价格
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CartServiceImplTest {

    private static final Long USER_ID = 1L;
    private static final String CART_KEY = "user:cart:" + USER_ID;

    @Mock
    private RedisTemplate redisTemplate;

    @Mock
    private HashOperations<String, String, String> hashOperations;

    @Mock
    private RemoteProductService remoteProductService;

    @InjectMocks
    private CartServiceImpl cartService;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.set(SecurityConstants.DETAILS_USER_ID, USER_ID);
        doReturn(hashOperations).when(redisTemplate).opsForHash();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.remove();
    }

    @Test
    void addToCart_existingItem_accumulatesNumWithoutRemoteCall() {
        // 购物车中已有该商品（数量2）
        CartInfo existing = new CartInfo();
        existing.setSkuId(101L);
        existing.setSkuName("商品A");
        existing.setSkuNum(2);
        existing.setSkuPrice(new BigDecimal("10"));
        when(hashOperations.get(CART_KEY, "101")).thenReturn(JSON.toJSONString(existing));

        cartService.addToCart(101L, 3);

        // 数量应累加为5写回Redis
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(hashOperations).put(eq(CART_KEY), eq("101"), captor.capture());
        CartInfo updated = JSON.parseObject(captor.getValue(), CartInfo.class);
        assertThat(updated.getSkuNum()).isEqualTo(5);

        // 已有商品不调商品服务（省一次远程调用）
        verify(remoteProductService, never()).getProductSku(any(), any());
    }

    @Test
    void addToCart_newItem_fetchesRemoteAndSaves() {
        // 购物车中无该商品
        when(hashOperations.get(CART_KEY, "102")).thenReturn(null);

        ProductSku productSku = new ProductSku();
        productSku.setSkuName("商品B");
        productSku.setSalePrice(new BigDecimal("20"));
        productSku.setThumbImg("http://img/b.png");
        when(remoteProductService.getProductSku(102L, SecurityConstants.INNER)).thenReturn(R.ok(productSku));

        cartService.addToCart(102L, 1);

        // 新商品写入Hash：默认选中、数量为传入值
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(hashOperations).put(eq(CART_KEY), eq("102"), captor.capture());
        CartInfo saved = JSON.parseObject(captor.getValue(), CartInfo.class);
        assertThat(saved.getSkuNum()).isEqualTo(1);
        assertThat(saved.getIsChecked()).isEqualTo(1);
        assertThat(saved.getCartPrice()).isEqualByComparingTo(new BigDecimal("20"));
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
    }

    @Test
    void getCartList_refreshesSkuPriceFromRemote() {
        // Redis中两条购物车记录，商品A价格已从10变为12
        CartInfo c1 = new CartInfo();
        c1.setSkuId(101L);
        c1.setSkuName("商品A");
        c1.setSkuNum(1);
        c1.setSkuPrice(new BigDecimal("10"));

        CartInfo c2 = new CartInfo();
        c2.setSkuId(102L);
        c2.setSkuName("商品B");
        c2.setSkuNum(2);
        c2.setSkuPrice(new BigDecimal("20"));

        when(hashOperations.values(CART_KEY)).thenReturn(
                Arrays.asList(JSON.toJSONString(c1), JSON.toJSONString(c2)));

        SkuPrice p1 = new SkuPrice();
        p1.setSkuId(101L);
        p1.setSalePrice(new BigDecimal("12"));
        SkuPrice p2 = new SkuPrice();
        p2.setSkuId(102L);
        p2.setSalePrice(new BigDecimal("20"));
        when(remoteProductService.getSkuPriceList(any(), eq(SecurityConstants.INNER)))
                .thenReturn(R.ok(Arrays.asList(p1, p2)));

        List<CartInfo> result = cartService.getCartList();

        // 返回列表价格已刷新
        assertThat(result).hasSize(2);
        CartInfo updated1 = result.stream().filter(c -> c.getSkuId().equals(101L)).findFirst().orElseThrow();
        assertThat(updated1.getSkuPrice()).isEqualByComparingTo(new BigDecimal("12"));

        // 刷新后的价格回写Redis（2条各一次）
        verify(hashOperations, times(2)).put(eq(CART_KEY), any(), any());
    }
}
