package dev.javacalc.calc;

import com.fasterxml.jackson.annotation.JsonFormat;
import dev.javacalc.calc.Outcome.BadOperands;
import dev.javacalc.calc.Outcome.Ok;
import dev.javacalc.calc.Outcome.UnknownOperation;
import dev.javacalc.calc.Outcome.Undefined;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class CalculatorController {

    /**
     * Request body. A bare number is accepted as a one-element list, so both
     * {@code {"operands": 81}} and {@code {"operands": [5, 10]}} work.
     */
    public record Request(
            @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<BigDecimal> operands) { }

    /**
     * The result is a decimal string, not a JSON number, so no precision is lost
     * in a client that parses numbers as IEEE 754 doubles.
     */
    public record Response(String result) { }

    private final Calculator calculator;

    public CalculatorController(Calculator calculator) {
        this.calculator = calculator;
    }

    @GetMapping("/operations")
    public List<String> operations() {
        return List.copyOf(Operations.BY_PATH.keySet());
    }

    @PostMapping("/{operation}")
    public ResponseEntity<?> calculate(@PathVariable String operation, @RequestBody Request request) {
        return switch (calculator.evaluate(operation, request.operands())) {
            case Ok(var value) -> ResponseEntity.ok(new Response(plain(value)));
            case UnknownOperation(var name) -> problem(HttpStatus.NOT_FOUND, "Unknown operation '" + name + "'");
            case BadOperands(var reason) -> problem(HttpStatus.BAD_REQUEST, reason);
            case Undefined(var reason) -> problem(HttpStatus.UNPROCESSABLE_CONTENT, reason);
        };
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
    }
}
