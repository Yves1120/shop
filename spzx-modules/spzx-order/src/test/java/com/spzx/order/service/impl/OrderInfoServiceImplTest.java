package com.spzx.order.service.impl;

import com.spzx.cart.api.RemoteCartService;
import com.spzx.cart.api.domain.CartInfo;
import com.spzx.common.core.constant.SecurityConstants;
import com.spzx.common.core.context.SecurityContextHolder;
import com.spzx.common.core.domain.R;
import com.spzx.common.core.exception.ServiceException;
import com.spzx.common.rabbit.constant.MqConst;
import com.spzx.common.rabbit.service.RabbitService;
import com.spzx.order.api.domain.OrderInfo;
import com.spzx.order.api.domain.OrderItem;
import com.spzx.order.domain.OrderLog;
import com.spzx.order.domain.vo.CheckoutSnapshot;
import com.spzx.order.domain.vo.OrderForm;
import com.spzx.order.domain.vo.TradeVo;
import com.spzx.order.mapper.OrderInfoMapper;
import com.spzx.order.mapper.OrderItemMapper;
import com.spzx.order.mapper.OrderLogMapper;
import com.spzx.product.api.RemoteProductService;
import com.spzx.product.api.domain.ProductSku;
import com.spzx.user.api.RemoteUserAddressService;
import com.spzx.user.domain.UserAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订单提交核心逻辑单元测试（纯Mock，不依赖数据库/Redis/MQ环境）
 *
 * 覆盖场景：
 * 1.交易号校验（防重复提交的核心防线）
 * 2.购物车空校验
 * 3.收货地址校验
 * 4.正常提交（订单落库、金额计算、延迟消息、购物车清空）
 */
@ExtendWith(MockitoExtension.class)
class OrderInfoServiceImplTest {

    private static final Long USER_ID = 1L;
    private static final String TRADE_KEY = "user:trade:" + USER_ID;

    @Mock
    private OrderInfoMapper orderInfoMapper;

    @Mock
    private OrderItemMapper orderItemMapper;

    @Mock
    private OrderLogMapper orderLogMapper;

    @Mock
    private RemoteCartService remoteCartService;

    @Mock
    private RemoteUserAddressService remoteUserAddressService;

    @Mock
    private RemoteProductService remoteProductService;

    @Mock
    private RabbitService rabbitService;

    @Mock
    private RedisTemplate redisTemplate;

    @Mock
    private ValueOperations valueOperations;

    @InjectMocks
    private OrderInfoServiceImpl orderInfoService;

    @BeforeEach
    void setUp() {
        // 模拟网关解析JWT后向上下文写入的用户ID
        SecurityContextHolder.set(SecurityConstants.DETAILS_USER_ID, USER_ID);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.remove();
    }

    private OrderForm buildOrderForm(String tradeNo) {
        OrderForm orderForm = new OrderForm();
        orderForm.setTradeNo(tradeNo);
        orderForm.setUserAddressId(10L);
        return orderForm;
    }

    @Test
    void submitOrder_tradeNoExpired_throwsAndNotQueryCart() {
        // Redis中交易号已过期（返回null）
        doReturn(valueOperations).when(redisTemplate).opsForValue();
        when(valueOperations.get(TRADE_KEY)).thenReturn(null);

        assertThatThrownBy(() -> orderInfoService.submitOrder(buildOrderForm("any")))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("交易号不匹配或已过期");

        // 交易号校验失败必须短路，不能再查购物车
        verify(remoteCartService, never()).getAllChecked(any());
    }

    @Test
    void submitOrder_tradeNoMismatch_throws() {
        doReturn(valueOperations).when(redisTemplate).opsForValue();
        when(valueOperations.get(TRADE_KEY)).thenReturn(buildSnapshot("redis-trade-no", true));

        assertThatThrownBy(() -> orderInfoService.submitOrder(buildOrderForm("fake-trade-no")))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("交易号不匹配或已过期");
    }

    @Test
    void submitOrder_snapshotTradeNoMissing_throwsServiceException() {
        doReturn(valueOperations).when(redisTemplate).opsForValue();
        when(valueOperations.get(TRADE_KEY)).thenReturn(buildSnapshot(null, true));

        assertThatThrownBy(() -> orderInfoService.submitOrder(buildOrderForm("trade-no")))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("交易号不匹配或已过期");
    }

    @Test
    void submitOrder_emptySnapshot_throws() {
        doReturn(valueOperations).when(redisTemplate).opsForValue();
        when(valueOperations.get(TRADE_KEY))
                .thenReturn(buildSnapshot("valid-trade-no", Collections.emptyList(), true));

        assertThatThrownBy(() -> orderInfoService.submitOrder(buildOrderForm("valid-trade-no")))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("结算商品为空");

        verify(redisTemplate, never()).delete(TRADE_KEY);
    }

    @Test
    void submitOrder_addressNotExist_throws() {
        doReturn(valueOperations).when(redisTemplate).opsForValue();
        when(valueOperations.get(TRADE_KEY)).thenReturn(buildSnapshot("valid-trade-no", true));
        when(remoteUserAddressService.getUserAddress(10L, SecurityConstants.INNER)).thenReturn(R.ok(null));

        assertThatThrownBy(() -> orderInfoService.submitOrder(buildOrderForm("valid-trade-no")))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("收货地址不存在");

        verify(redisTemplate, never()).delete(TRADE_KEY);
    }

    @Test
    void submitOrder_otherUsersAddress_throws() {
        doReturn(valueOperations).when(redisTemplate).opsForValue();
        when(valueOperations.get(TRADE_KEY)).thenReturn(buildSnapshot("valid-trade-no", true));
        UserAddress address = buildAddress();
        address.setUserId(99L);
        when(remoteUserAddressService.getUserAddress(10L, SecurityConstants.INNER)).thenReturn(R.ok(address));

        assertThatThrownBy(() -> orderInfoService.submitOrder(buildOrderForm("valid-trade-no")))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("无权使用该收货地址");

        verify(orderInfoMapper, never()).insert(any(OrderInfo.class));
    }

    @Test
    void submitOrder_success_savesOrderAndSendsDelayMessage() {
        // ---- 准备Mock数据 ----
        doReturn(valueOperations).when(redisTemplate).opsForValue();
        when(valueOperations.get(TRADE_KEY)).thenReturn(buildSnapshot("valid-trade-no", true));
        when(remoteUserAddressService.getUserAddress(10L, SecurityConstants.INNER)).thenReturn(R.ok(buildAddress()));
        when(remoteCartService.clearCheckedCart(SecurityConstants.INNER)).thenReturn(R.ok());

        // 模拟MyBatis-Plus插入后回填自增ID
        when(orderInfoMapper.insert(any(OrderInfo.class))).thenAnswer(invocation -> {
            OrderInfo orderInfo = invocation.getArgument(0);
            orderInfo.setId(100L);
            return 1;
        });

        // ---- 执行 ----
        Long orderId = orderInfoService.submitOrder(buildOrderForm("valid-trade-no"));

        // ---- 验证 ----
        assertThat(orderId).isEqualTo(100L);

        // 验证订单主表落库：金额=10*2 + 20*1 = 40
        ArgumentCaptor<OrderInfo> orderCaptor = ArgumentCaptor.forClass(OrderInfo.class);
        verify(orderInfoMapper).insert(orderCaptor.capture());
        OrderInfo savedOrder = orderCaptor.getValue();
        assertThat(savedOrder.getTotalAmount()).isEqualByComparingTo(new BigDecimal("40"));
        assertThat(savedOrder.getOrderStatus()).isZero();
        assertThat(savedOrder.getUserId()).isEqualTo(USER_ID);
        assertThat(savedOrder.getOrderNo()).isNotBlank();

        // 验证订单项落库（2条）
        verify(orderItemMapper, times(2)).insert(any(OrderItem.class));

        // 验证订单日志落库
        verify(orderLogMapper).insert(any(OrderLog.class));

        // 验证交易号已删除（一次性使用，防重复提交）
        verify(redisTemplate).delete(TRADE_KEY);

        // 验证已清除选中购物车
        verify(remoteCartService).clearCheckedCart(SecurityConstants.INNER);

        // 验证发送了30分钟延迟消息用于超时自动取消
        verify(rabbitService).sendDelayMessage(
                eq(MqConst.EXCHANGE_CANCEL_ORDER), eq(MqConst.ROUTING_CANCEL_ORDER), eq(100L), eq(30 * 60 * 1000));
    }

    @Test
    void submitOrder_mqSendFailed_shouldNotAffectOrderCreation() throws Exception {
        doReturn(valueOperations).when(redisTemplate).opsForValue();
        when(valueOperations.get(TRADE_KEY)).thenReturn(buildSnapshot("valid-trade-no", true));
        when(remoteUserAddressService.getUserAddress(10L, SecurityConstants.INNER)).thenReturn(R.ok(buildAddress()));
        when(remoteCartService.clearCheckedCart(SecurityConstants.INNER)).thenReturn(R.ok());
        when(orderInfoMapper.insert(any(OrderInfo.class))).thenAnswer(invocation -> {
            ((OrderInfo) invocation.getArgument(0)).setId(101L);
            return 1;
        });
        // MQ发送异常被内部catch，订单创建不应失败（降级容错）
        when(rabbitService.sendDelayMessage(any(), any(), any(), anyInt()))
                .thenThrow(new RuntimeException("mq down"));

        Long orderId = orderInfoService.submitOrder(buildOrderForm("valid-trade-no"));

        assertThat(orderId).isEqualTo(101L);
    }

    @Test
    void buyAndSubmitOrder_usesSnapshotWithoutClearingCart() {
        doReturn(valueOperations).when(redisTemplate).opsForValue();
        ProductSku productSku = new ProductSku();
        productSku.setId(8L);
        productSku.setSkuName("立即购买商品");
        productSku.setThumbImg("http://img/buy.png");
        productSku.setSalePrice(new BigDecimal("88.50"));
        when(remoteProductService.getProductSku(8L, SecurityConstants.INNER)).thenReturn(R.ok(productSku));

        TradeVo tradeVo = orderInfoService.buy(8L);

        ArgumentCaptor<CheckoutSnapshot> snapshotCaptor = ArgumentCaptor.forClass(CheckoutSnapshot.class);
        verify(valueOperations).set(eq(TRADE_KEY), snapshotCaptor.capture(), eq(30L), eq(TimeUnit.MINUTES));
        CheckoutSnapshot snapshot = snapshotCaptor.getValue();
        assertThat(snapshot.isCartCheckout()).isFalse();
        assertThat(snapshot.getOrderItemList()).hasSize(1);
        assertThat(snapshot.getOrderItemList().get(0).getSkuPrice()).isEqualByComparingTo("88.50");

        when(valueOperations.get(TRADE_KEY)).thenReturn(snapshot);
        when(remoteUserAddressService.getUserAddress(10L, SecurityConstants.INNER)).thenReturn(R.ok(buildAddress()));
        when(orderInfoMapper.insert(any(OrderInfo.class))).thenAnswer(invocation -> {
            ((OrderInfo) invocation.getArgument(0)).setId(102L);
            return 1;
        });

        Long orderId = orderInfoService.submitOrder(buildOrderForm(tradeVo.getTradeNo()));

        assertThat(orderId).isEqualTo(102L);
        verify(remoteCartService, never()).getAllChecked(any());
        verify(remoteCartService, never()).clearCheckedCart(any());
    }

    @Test
    void cancelUnpaidOrder_updatesPendingOrderAsSystem() {
        when(orderInfoMapper.update(any(OrderInfo.class), any())).thenReturn(1);

        orderInfoService.cancelUnpaidOrder(200L);

        ArgumentCaptor<OrderLog> logCaptor = ArgumentCaptor.forClass(OrderLog.class);
        verify(orderLogMapper).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().getOperateUser()).isEqualTo("系统");
        assertThat(logCaptor.getValue().getNote()).contains("超时");
    }

    @Test
    void updateOrderStatus_pendingOrder_marksPaidAndWritesLog() {
        OrderInfo orderInfo = buildOrderInfo(300L, 0);
        when(orderInfoMapper.selectOne(any())).thenReturn(orderInfo);
        when(orderInfoMapper.update(any(OrderInfo.class), any())).thenReturn(1);

        orderInfoService.updateOrderStatus("ORDER-300", 1);

        ArgumentCaptor<OrderInfo> updateCaptor = ArgumentCaptor.forClass(OrderInfo.class);
        verify(orderInfoMapper).update(updateCaptor.capture(), any());
        assertThat(updateCaptor.getValue().getOrderStatus()).isEqualTo(1);
        assertThat(updateCaptor.getValue().getPaymentTime()).isNotNull();
        verify(orderLogMapper).insert(any(OrderLog.class));
    }

    @Test
    void updateOrderStatus_alreadyPaid_isIdempotent() {
        when(orderInfoMapper.selectOne(any())).thenReturn(buildOrderInfo(301L, 1));

        orderInfoService.updateOrderStatus("ORDER-301", 1);

        verify(orderInfoMapper, never()).update(any(OrderInfo.class), any());
        verify(orderLogMapper, never()).insert(any(OrderLog.class));
    }

    @Test
    void updateOrderStatus_cancelledOrder_rejectsPayment() {
        when(orderInfoMapper.selectOne(any())).thenReturn(buildOrderInfo(302L, -1));

        assertThatThrownBy(() -> orderInfoService.updateOrderStatus("ORDER-302", 1))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("不允许支付");

        verify(orderInfoMapper, never()).update(any(OrderInfo.class), any());
        verify(orderLogMapper, never()).insert(any(OrderLog.class));
    }

    // ---- 测试数据构造 ----

    private List<CartInfo> buildCartList() {
        CartInfo c1 = new CartInfo();
        c1.setSkuId(1L);
        c1.setSkuName("商品A");
        c1.setThumbImg("http://img/a.png");
        c1.setSkuPrice(new BigDecimal("10"));
        c1.setSkuNum(2);

        CartInfo c2 = new CartInfo();
        c2.setSkuId(2L);
        c2.setSkuName("商品B");
        c2.setThumbImg("http://img/b.png");
        c2.setSkuPrice(new BigDecimal("20"));
        c2.setSkuNum(1);

        List<CartInfo> list = new ArrayList<>();
        list.add(c1);
        list.add(c2);
        return list;
    }

    private CheckoutSnapshot buildSnapshot(String tradeNo, boolean cartCheckout) {
        List<OrderItem> items = new ArrayList<>();
        for (CartInfo cartInfo : buildCartList()) {
            OrderItem item = new OrderItem();
            item.setSkuId(cartInfo.getSkuId());
            item.setSkuName(cartInfo.getSkuName());
            item.setThumbImg(cartInfo.getThumbImg());
            item.setSkuPrice(cartInfo.getSkuPrice());
            item.setSkuNum(cartInfo.getSkuNum());
            items.add(item);
        }
        return buildSnapshot(tradeNo, items, cartCheckout);
    }

    private CheckoutSnapshot buildSnapshot(String tradeNo, List<OrderItem> items, boolean cartCheckout) {
        CheckoutSnapshot snapshot = new CheckoutSnapshot();
        snapshot.setTradeNo(tradeNo);
        snapshot.setOrderItemList(items);
        snapshot.setCartCheckout(cartCheckout);
        return snapshot;
    }

    private UserAddress buildAddress() {
        UserAddress address = new UserAddress();
        address.setId(10L);
        address.setUserId(USER_ID);
        address.setName("张三");
        address.setPhone("13800138000");
        address.setTagName("家");
        address.setProvinceCode("110000");
        address.setCityCode("110100");
        address.setDistrictCode("110101");
        address.setAddress("xx街道1号");
        return address;
    }

    private OrderInfo buildOrderInfo(Long orderId, Integer orderStatus) {
        OrderInfo orderInfo = new OrderInfo();
        orderInfo.setId(orderId);
        orderInfo.setOrderStatus(orderStatus);
        return orderInfo;
    }
}
