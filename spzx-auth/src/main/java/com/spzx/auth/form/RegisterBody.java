package com.spzx.auth.form;

import io.swagger.v3.oas.annotations.media.Schema;

public class RegisterBody extends LoginBody {

    @Schema(description = "昵称")
    private String nickName;

    @Schema(description = "验证码")
    private String code;

    @Schema(description = "验证码key")
    private String codeKey;

    public String getNickName() {
        return nickName;
    }

    public void setNickName(String nickName) {
        this.nickName = nickName;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getCodeKey() {
        return codeKey;
    }

    public void setCodeKey(String codeKey) {
        this.codeKey = codeKey;
    }
}