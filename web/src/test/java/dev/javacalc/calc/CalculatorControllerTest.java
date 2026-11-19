package dev.javacalc.calc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class CalculatorControllerTest {

    @Autowired
    MockMvc mvc;

    private ResultActions postJson(String path, String json) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void addsOperands() throws Exception {
        postJson("/api/add", "{\"operands\": [5, 10, 10]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("25"));
    }

    @Test
    void decimalsAreExact() throws Exception {
        postJson("/api/add", "{\"operands\": [0.1, 0.2]}")
                .andExpect(jsonPath("$.result").value("0.3"));
    }

    @Test
    void acceptsBareNumberForUnaryOperations() throws Exception {
        postJson("/api/sqroot", "{\"operands\": 81}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("9"));
    }

    @Test
    void piTakesNoOperands() throws Exception {
        postJson("/api/pi", "{\"operands\": []}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("3.141592653589793238462643383279503"));
        postJson("/api/pi", "{\"operands\": [1]}").andExpect(status().isBadRequest());
    }

    @Test
    void divisionByZeroIs422() throws Exception {
        postJson("/api/divide", "{\"operands\": [1, 0]}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Division by zero"));
    }

    @Test
    void wrongArityIs400() throws Exception {
        postJson("/api/add", "{\"operands\": [1]}").andExpect(status().isBadRequest());
    }

    @Test
    void malformedJsonIs400() throws Exception {
        postJson("/api/add", "{\"operands\": [1, \"x\"]}").andExpect(status().isBadRequest());
        postJson("/api/add", "not json").andExpect(status().isBadRequest());
    }

    @Test
    void unknownOperationIs404() throws Exception {
        postJson("/api/sine", "{\"operands\": [1]}").andExpect(status().isNotFound());
    }

    @Test
    void listsOperations() throws Exception {
        mvc.perform(get("/api/operations"))
                .andExpect(status().isOk())
                .andExpect(content().json("[\"add\",\"subtract\",\"multiply\",\"divide\",\"sqroot\",\"square\",\"inverse\",\"power\",\"pi\"]"));
    }
}
