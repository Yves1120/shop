package com.spzx.payment.service;

import com.spzx.common.core.web.domain.AjaxResult;
import java.util.Map;

public interface IAlipayService {

    AjaxResult submitAlipay(String orderNo);

    boolean notifyPayment(Map<String, String> params) throws Exception;

    AjaxResult refund(Long orderId);

    void mockPay(String orderNo);
}
