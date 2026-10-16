package com.spzx.user.domain;

import com.spzx.common.core.web.domain.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 用户浏览历史对象 user_browse_history
 *
 * @author atguigu
 * @date 2024-09-27
 */
@Data
@Schema(description = "用户浏览历史")
public class UserBrowseHistory extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "SKU商品ID")
    private Long skuId;

}
