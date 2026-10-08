package com.apg.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(classes = {CorrelationWebTest.App.class, CorrelationWebTest.Ctl.class})
@AutoConfigureMockMvc
class CorrelationWebTest {
    @org.springframework.context.annotation.Configuration
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration(exclude = {
            org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
            org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration.class,
            org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration.class})
    static class App {}
    @RestController static class Ctl {
        @GetMapping("/boom") String boom() { throw new IllegalStateException("secret internals"); }
        @org.springframework.web.bind.annotation.PostMapping(value = "/echo", consumes = "application/json")
        String echo(@org.springframework.web.bind.annotation.RequestBody java.util.Map<String, Object> b) { return "ok"; }
    }

    @Autowired MockMvc mvc;

    @Test void echoesValidCorrelationId() throws Exception {
        mvc.perform(get("/boom").header("X-Correlation-Id", "abc12345-xyz"))
           .andExpect(header().string("X-Correlation-Id", "abc12345-xyz"));
    }
    @Test void springProtocolErrorsKeepTheirStatusInsteadOfBecoming500() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/boom")
                .contentType("text/plain").content("x"))
           .andExpect(status().isMethodNotAllowed());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/echo")
                .contentType("text/plain").content("x"))
           .andExpect(status().isUnsupportedMediaType())
           .andExpect(jsonPath("$.error.code").value("HTTP_415"));
    }

    @Test void generatesIdAndHidesInternalErrorDetails() throws Exception {
        mvc.perform(get("/boom"))
           .andExpect(status().isInternalServerError())
           .andExpect(jsonPath("$.success").value(false))
           .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
           .andExpect(jsonPath("$.error.message").value("Unexpected error"))
           .andExpect(jsonPath("$.requestId").value(org.hamcrest.Matchers.startsWith("req_")));
    }
}
