package com.spzx.order.domain.vo;

import com.spzx.order.api.domain.OrderItem;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class CheckoutSnapshot implements Serializable {

    private static final long serialVersionUID = 1L;

    private String tradeNo;

    private List<OrderItem> orderItemList;

    private boolean cartCheckout;
}
