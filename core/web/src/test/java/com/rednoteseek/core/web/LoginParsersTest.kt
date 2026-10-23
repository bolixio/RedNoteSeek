package com.rednoteseek.core.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoginParsersTest {

    @Test fun guestUserMe() {
        assertEquals(
            LoginState.Guest,
            LoginParsers.loginState("""{"code":0,"success":true,"msg":"成功","data":{"user_id":"6ad3","guest":true,"xsec_token":"AB="}}"""),
        )
    }

    @Test fun loggedInUserMe() {
        assertEquals(
            LoginState.LoggedIn(userId = "u9", nickname = "测试用户", avatar = "https://x/a.jpg"),
            LoginParsers.loginState("""{"code":0,"success":true,"data":{"user_id":"u9","guest":false,"nickname":"测试用户","red_id":"123","images":"https://x/a.jpg"}}"""),
        )
    }

    @Test fun avatarUpgradedToHttpsAndDefaultsEmpty() {
        assertEquals(
            "https://sns-avatar-qc.xhscdn.com/avatar/a.jpg",
            (LoginParsers.loginState("""{"data":{"user_id":"u9","guest":false,"images":"http://sns-avatar-qc.xhscdn.com/avatar/a.jpg"}}""") as LoginState.LoggedIn).avatar,
        )
        assertEquals(
            "",
            (LoginParsers.loginState("""{"data":{"user_id":"u9","guest":false}}""") as LoginState.LoggedIn).avatar,
        )
    }

    @Test fun noGuestFieldMeansUnknown() {
        assertNull(LoginParsers.loginState("""{"code":-100,"success":false,"msg":"登录已过期"}"""))
        assertNull(LoginParsers.loginState(""))
    }

    @Test fun qrLoginUrl() {
        assertEquals(
            "https://www.xiaohongshu.com/mobile/login?qrId=1&ruleId=4",
            LoginParsers.qrLoginUrl("""{"code":0,"success":true,"data":{"qr_id":"1","code":"2","url":"https://www.xiaohongshu.com/mobile/login?qrId=1&ruleId=4","multi_flag":0}}"""),
        )
    }

    @Test fun qrLoginUrlMissing() {
        assertNull(LoginParsers.qrLoginUrl("""{"code":0,"data":{}}"""))
        assertNull(LoginParsers.qrLoginUrl("""{"code":0,"data":{"url":""}}"""))
    }
}
