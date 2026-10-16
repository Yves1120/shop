package com.spzx.payment.controller;

import com.alipay.api.internal.util.AlipaySignature;
import com.spzx.common.core.web.controller.BaseController;
import com.spzx.common.core.web.domain.AjaxResult;
import com.spzx.common.security.annotation.RequiresLogin;
import com.spzx.payment.configure.AlipayConfig;
import com.spzx.payment.service.IAlipayService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/alipay")
public class AlipayController extends BaseController {

    @Autowired
    private IAlipayService alipayService;

    /**
     * H5端：提交支付宝支付
     */
    @Operation(summary = "提交支付宝支付")
    @RequiresLogin
    @GetMapping("/submitAlipay/{orderNo}")
    public AjaxResult submitAlipay(@PathVariable String orderNo) {
        return alipayService.submitAlipay(orderNo);
    }

    /**
     * 支付宝异步回调通知
     */
    @Operation(summary = "支付宝异步回调通知")
    @PostMapping("/notify")
    public String notifyPayment(HttpServletRequest request) {
        try {
            Map<String, String> params = new HashMap<>();
            Map<String, String[]> requestParams = request.getParameterMap();
            for (String name : requestParams.keySet()) {
                String[] values = requestParams.get(name);
                StringBuilder valueStr = new StringBuilder();
                for (int i = 0; i < values.length; i++) {
                    valueStr.append(i == values.length - 1 ? values[i] : values[i] + ",");
                }
                params.put(name, valueStr.toString());
            }
            return alipayService.notifyPayment(params) ? "success" : "fail";
        } catch (Exception e) {
            log.error("支付宝回调处理异常", e);
            return "fail";
        }
    }

    /**
     * 支付宝同步回调
     */
    @Operation(summary = "支付宝同步回调")
    @GetMapping("/return")
    public String returnPayment(HttpServletRequest request) {
        return "支付完成，请返回APP查看订单状态";
    }

    /**
     * 模拟支付（沙箱不可用时使用）
     */
    @Operation(summary = "模拟支付")
    @RequiresLogin
    @PostMapping("/mockPay")
    public String mockPay(@RequestParam String out_trade_no) {
        try {
            alipayService.mockPay(out_trade_no);
            return "<html><body><h2>模拟支付成功</h2><p>订单号：" + out_trade_no + "</p><script>setTimeout(function(){window.location.href='" + AlipayConfig.return_order_url + "';},2000);</script></body></html>";
        } catch (Exception e) {
            log.error("模拟支付异常", e);
            return "<html><body><h2>模拟支付失败</h2><p>" + e.getMessage() + "</p></body></html>";
        }
    }
}
