package com.spzx.payment.service.impl;

import com.spzx.common.core.constant.SecurityConstants;
import com.spzx.common.core.context.SecurityContextHolder;
import com.spzx.common.core.domain.R;
import com.spzx.common.core.exception.ServiceException;
import com.spzx.order.api.RemoteOrderInfoService;
import com.spzx.order.api.domain.OrderInfo;
import com.spzx.payment.domain.PaymentInfo;
import com.spzx.payment.service.IPaymentInfoService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlipayServiceImplTest {

    private static final Long USER_ID = 1L;
    private static final String ORDER_NO = "ORDER-1";

    @Mock
    private IPaymentInfoService paymentInfoService;

    @Mock
    private RemoteOrderInfoService remoteOrderInfoService;

    @InjectMocks
    private AlipayServiceImpl alipayService;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.set(SecurityConstants.DETAILS_USER_ID, USER_ID);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.remove();
    }

    @Test
    void mockPay_updatesRemoteOrderBeforeLocalPaymentRecord() {
        PaymentInfo paymentInfo = buildPaymentInfo();
        when(remoteOrderInfoService.getByOrderNo(ORDER_NO, "inner")).thenReturn(R.ok(buildOrderInfo(0)));
        when(paymentInfoService.getOne(any())).thenReturn(paymentInfo);
        when(remoteOrderInfoService.updateOrderStatus(ORDER_NO, 1, "inner")).thenReturn(R.ok());
        when(paymentInfoService.updateById(paymentInfo)).thenReturn(true);

        alipayService.mockPay(ORDER_NO);

        InOrder inOrder = inOrder(remoteOrderInfoService, paymentInfoService);
        inOrder.verify(remoteOrderInfoService).updateOrderStatus(ORDER_NO, 1, "inner");
        inOrder.verify(paymentInfoService).updateById(paymentInfo);
    }

    @Test
    void mockPay_remoteOrderUpdateFails_doesNotMarkLocalPaymentSuccessful() {
        PaymentInfo paymentInfo = buildPaymentInfo();
        when(remoteOrderInfoService.getByOrderNo(ORDER_NO, "inner")).thenReturn(R.ok(buildOrderInfo(0)));
        when(paymentInfoService.getOne(any())).thenReturn(paymentInfo);
        when(remoteOrderInfoService.updateOrderStatus(ORDER_NO, 1, "inner")).thenReturn(R.fail("update failed"));

        assertThatThrownBy(() -> alipayService.mockPay(ORDER_NO))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("更新订单支付状态失败");

        verify(paymentInfoService, never()).updateById(any(PaymentInfo.class));
    }

    private OrderInfo buildOrderInfo(Integer orderStatus) {
        OrderInfo orderInfo = new OrderInfo();
        orderInfo.setUserId(USER_ID);
        orderInfo.setOrderStatus(orderStatus);
        orderInfo.setTotalAmount(new BigDecimal("99.00"));
        return orderInfo;
    }

    private PaymentInfo buildPaymentInfo() {
        PaymentInfo paymentInfo = new PaymentInfo();
        paymentInfo.setOrderNo(ORDER_NO);
        paymentInfo.setPaymentStatus("0");
        paymentInfo.setAmount(new BigDecimal("99.00"));
        return paymentInfo;
    }
}
