package com.vsec.config;

import com.vsec.exception.VsecException;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // M3: VsecException 的消息是安全的，可返回给客户端
    @ExceptionHandler(VsecException.class)
    public ResponseEntity<Map<String, String>> handleVsec(VsecException e) {
        log.warn("业务异常: {}", e.getMessage());
        return ResponseEntity
                .status(e.getStatus())
                .body(Map.of("message", e.getMessage()));
    }

    // M3: 普通 RuntimeException 返回通用错误消息，详情仅记录日志
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleRuntime(RuntimeException e) {
        log.warn("请求异常: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", "请求处理失败"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", msg));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneral(Exception e) {
        log.error("未预期异常", e);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "服务器内部错误"));
    }

    @ExceptionHandler(java.util.concurrent.CompletionException.class)
    public void handleCompletionException(java.util.concurrent.CompletionException e,
                                           HttpServletResponse response) throws IOException {
        Throwable cause = e.getCause();
        String msg = cause != null ? cause.getMessage() : "流处理错误";
        log.error("流式响应异常: {}", msg, cause);
        if (!response.isCommitted()) {
            response.sendError(HttpStatus.INTERNAL_SERVER_ERROR.value(), msg);
        }
    }
}
