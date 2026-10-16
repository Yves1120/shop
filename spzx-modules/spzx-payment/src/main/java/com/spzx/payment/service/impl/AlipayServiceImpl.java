package com.spzx.payment.service.impl;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.spzx.common.core.domain.R;
import com.spzx.common.core.exception.ServiceException;
import com.spzx.common.core.web.domain.AjaxResult;
import com.spzx.common.security.utils.SecurityUtils;
import com.spzx.order.api.RemoteOrderInfoService;
import com.spzx.order.api.domain.OrderInfo;
import com.spzx.payment.configure.AlipayConfig;
import com.spzx.payment.domain.PaymentInfo;
import com.spzx.payment.service.IAlipayService;
import com.spzx.payment.service.IPaymentInfoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class AlipayServiceImpl implements IAlipayService {

    @Autowired
    AlipayClient alipayClient;

    @Autowired
    IPaymentInfoService paymentInfoService;

    @Autowired
    RemoteOrderInfoService remoteOrderInfoService;

    @Autowired
    RedisTemplate redisTemplate;

    @Override
    public AjaxResult submitAlipay(String orderNo) {
        try {
            // 获取订单信息
            R<OrderInfo> orderResult = remoteOrderInfoService.getByOrderNo(orderNo, "inner");
            if (orderResult.getCode() != 200 || orderResult.getData() == null) {
                return AjaxResult.error("订单不存在");
            }
            OrderInfo orderInfo = orderResult.getData();

            // 校验订单归属：确保当前登录用户只能支付自己的订单
            Long currentUserId = SecurityUtils.getUserId();
            if (!orderInfo.getUserId().equals(currentUserId)) {
                return AjaxResult.error("无权操作他人订单");
            }

            // 防重复支付：只有未支付(orderStatus=0)的订单才允许发起支付
            if (orderInfo.getOrderStatus() != null && orderInfo.getOrderStatus() != 0) {
                return AjaxResult.error("订单已支付或已取消，请勿重复支付");
            }

            // Redis防重锁：5秒内同一订单只能提交一次支付请求，防止用户快速重复点击
            String payLockKey = "pay:lock:" + orderNo;
            if (!Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(payLockKey, "1", 5, TimeUnit.SECONDS))) {
                return AjaxResult.error("支付请求处理中，请勿重复提交");
            }

            // 查询是否已有未支付的支付记录，有则复用，避免重复创建多条记录
            PaymentInfo paymentInfo = paymentInfoService.getOne(
                    new LambdaQueryWrapper<PaymentInfo>()
                            .eq(PaymentInfo::getOrderNo, orderNo)
                            .eq(PaymentInfo::getPaymentStatus, "0"));
            if (paymentInfo == null) {
                // 首次发起支付，创建支付记录
                paymentInfo = new PaymentInfo();
                paymentInfo.setUserId(orderInfo.getUserId());
                paymentInfo.setOrderNo(orderNo);
                paymentInfo.setPayType(2); // 支付宝
                paymentInfo.setAmount(orderInfo.getTotalAmount());
                paymentInfo.setContent("支付宝支付：" + orderNo);
                paymentInfo.setPaymentStatus("0"); // 未支付
                paymentInfoService.save(paymentInfo);
            }

            // 尝试调用支付宝沙箱，失败则降级到模拟支付
            String form = null;
            try {
                AlipayTradePagePayRequest alipayRequest = new AlipayTradePagePayRequest();
                alipayRequest.setReturnUrl(AlipayConfig.return_payment_url);
                alipayRequest.setNotifyUrl(AlipayConfig.notify_payment_url);
                alipayRequest.setBizContent("{\"out_trade_no\":\"" + orderNo + "\","
                        + "\"total_amount\":\"" + orderInfo.getTotalAmount() + "\","
                        + "\"subject\":\"尚品甄选-订单支付\","
                        + "\"product_code\":\"FAST_INSTANT_TRADE_PAY\"}");
                form = alipayClient.pageExecute(alipayRequest).getBody();
            } catch (Exception e) {
                log.warn("支付宝沙箱不可用，使用模拟支付: {}", e.getMessage());
            }

            if (form == null || form.isEmpty()) {
                form = buildMockPayForm(orderNo);
            }
            return AjaxResult.success("", form);
        } catch (Exception e) {
            log.error("支付提交异常", e);
            return AjaxResult.error("支付提交失败：" + e.getMessage());
        }
    }

    @Override
    public boolean notifyPayment(Map<String, String> params) throws Exception {
        String orderNo = params.get("out_trade_no");
        String tradeNo = params.get("trade_no");
        String tradeStatus = params.get("trade_status");

        if (!"TRADE_SUCCESS".equals(tradeStatus)) {
            return true;
        }

        // 回调必须通过支付宝签名验证，不允许任何跳过签名的逻辑
        boolean signVerified;
        try {
            signVerified = AlipaySignature.rsaCheckV1(params,
                    AlipayConfig.alipay_public_key,
                    AlipayConfig.charset,
                    AlipayConfig.sign_type);
        } catch (Exception e) {
            log.error("签名验证异常: {}", e.getMessage());
            signVerified = false;
        }

        if (!signVerified) {
            log.warn("支付宝回调签名验证失败，拒绝处理: orderNo={}", orderNo);
            return false;
        }

        // 查询支付记录，不存在则拒绝（防止伪造未发起支付的订单）
        PaymentInfo paymentInfo = paymentInfoService.getOne(
                new LambdaQueryWrapper<PaymentInfo>().eq(PaymentInfo::getOrderNo, orderNo));
        if (paymentInfo == null) {
            log.warn("支付记录不存在，拒绝处理: orderNo={}", orderNo);
            return false;
        }

        // 幂等检查：已处理的回调直接返回，支付宝会重发多次通知
        if ("1".equals(paymentInfo.getPaymentStatus())) {
            log.info("订单{}的支付回调已处理过，跳过重复处理", orderNo);
            return true;
        }

        // 金额校验：回调金额必须与支付记录金额一致
        String totalAmount = params.get("total_amount");
        if (StringUtils.hasText(totalAmount)
                && new BigDecimal(totalAmount).compareTo(paymentInfo.getAmount()) != 0) {
            log.warn("支付金额不一致，拒绝处理: orderNo={}, 回调金额={}, 订单金额={}",
                    orderNo, totalAmount, paymentInfo.getAmount());
            return false;
        }

        R<Void> orderResult = remoteOrderInfoService.updateOrderStatus(orderNo, 1, "inner");
        if (orderResult == null || R.isError(orderResult)) {
            log.error("更新订单支付状态失败: orderNo={}", orderNo);
            return false;
        }

        paymentInfo.setPaymentStatus("1");
        paymentInfo.setTradeNo(tradeNo);
        paymentInfo.setCallbackTime(new Date());
        paymentInfo.setCallbackContent(params.toString());
        return paymentInfoService.updateById(paymentInfo);
    }

    @Override
    public void mockPay(String orderNo) {
        // 校验订单归属：模拟支付仅允许支付自己的订单
        R<OrderInfo> orderResult = remoteOrderInfoService.getByOrderNo(orderNo, "inner");
        if (orderResult.getCode() != 200 || orderResult.getData() == null) {
            throw new ServiceException("订单不存在");
        }
        OrderInfo orderInfo = orderResult.getData();
        Long currentUserId = SecurityUtils.getUserId();
        if (!orderInfo.getUserId().equals(currentUserId)) {
            throw new ServiceException("无权操作他人订单");
        }
        if (orderInfo.getOrderStatus() != null
                && orderInfo.getOrderStatus() != 0
                && orderInfo.getOrderStatus() != 1) {
            throw new ServiceException("订单已取消或当前状态不允许支付");
        }

        // 复用或创建支付记录
        PaymentInfo paymentInfo = paymentInfoService.getOne(
                new LambdaQueryWrapper<PaymentInfo>()
                        .eq(PaymentInfo::getOrderNo, orderNo));
        if (paymentInfo == null) {
            paymentInfo = new PaymentInfo();
            paymentInfo.setUserId(orderInfo.getUserId());
            paymentInfo.setOrderNo(orderNo);
            paymentInfo.setPayType(2); // 支付宝
            paymentInfo.setAmount(orderInfo.getTotalAmount());
            paymentInfo.setContent("模拟支付：" + orderNo);
            paymentInfo.setPaymentStatus("0");
            paymentInfoService.save(paymentInfo);
        }

        // 先更新订单，订单服务会拒绝已取消订单，并对重复支付做幂等处理
        R<Void> updateResult = remoteOrderInfoService.updateOrderStatus(orderNo, 1, "inner");
        if (updateResult == null || R.isError(updateResult)) {
            throw new ServiceException("更新订单支付状态失败");
        }

        if ("1".equals(paymentInfo.getPaymentStatus())) {
            return;
        }

        // 订单更新成功后再标记本地支付记录，避免远程失败时产生假成功记录
        paymentInfo.setPaymentStatus("1");
        paymentInfo.setTradeNo("MOCK_" + System.currentTimeMillis());
        paymentInfo.setCallbackTime(new Date());
        paymentInfo.setCallbackContent("mock pay");
        if (!paymentInfoService.updateById(paymentInfo)) {
            throw new ServiceException("更新支付记录失败");
        }
    }

    @Override
    public AjaxResult refund(Long orderId) {
        return AjaxResult.error("退款功能暂未实现");
    }

    /**
     * 构建模拟支付表单（当支付宝沙箱不可用时使用）
     */
    private String buildMockPayForm(String orderNo) {
        return "<form name='alipaysubmit' method='POST' action='/payment/alipay/mockPay'>"
                + "<input type='hidden' name='out_trade_no' value='" + orderNo + "'/>"
                + "<input type='submit' value='模拟支付' style='display:none'/>"
                + "</form>"
                + "<script>document.forms[0].submit();</script>";
    }
}
