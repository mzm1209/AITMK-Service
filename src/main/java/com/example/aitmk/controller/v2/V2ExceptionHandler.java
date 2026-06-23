package com.example.aitmk.controller.v2;

import com.example.aitmk.model.api.v2.*;
import jakarta.servlet.*;import jakarta.servlet.http.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;import org.springframework.core.annotation.Order;
import org.springframework.http.*;import org.springframework.stereotype.Component;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.*;import org.springframework.web.bind.annotation.*;import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.io.IOException;import java.util.UUID;

@Component @Order(Ordered.HIGHEST_PRECEDENCE)
class V2RequestIdFilter implements Filter {
 public void doFilter(ServletRequest r,ServletResponse s,FilterChain c)throws IOException,ServletException{var h=(HttpServletRequest)r;if(h.getRequestURI().startsWith("/api/v2")){String id=h.getHeader("X-Request-Id");h.setAttribute("v2RequestId",id==null||id.isBlank()?UUID.randomUUID().toString():id);((HttpServletResponse)s).setHeader("X-Request-Id",RequestIds.current());}c.doFilter(r,s);}
}

@Slf4j @RestControllerAdvice(basePackages="com.example.aitmk.controller.v2")
public class V2ExceptionHandler {
 @ExceptionHandler(V2Exception.class) ResponseEntity<V2Api.Failure> business(V2Exception e){return failure(e.getStatus(),e.getCode(),e.getMessage(),e.getDetails());}
 @ExceptionHandler({MethodArgumentNotValidException.class,MissingServletRequestParameterException.class,MissingRequestHeaderException.class,MethodArgumentTypeMismatchException.class,HttpMessageNotReadableException.class,IllegalArgumentException.class}) ResponseEntity<V2Api.Failure> bad(Exception e){log.debug("Invalid v2 request requestId={}",RequestIds.current(),e);return failure(HttpStatus.BAD_REQUEST,"INVALID_ARGUMENT","请求参数无效",null);}
 @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class) ResponseEntity<V2Api.Failure> conflict(Exception e){return failure(HttpStatus.CONFLICT,"VERSION_CONFLICT","数据已被其他操作更新",null);}
 @ExceptionHandler(NoHandlerFoundException.class) ResponseEntity<V2Api.Failure> notFound(Exception e){return failure(HttpStatus.NOT_FOUND,"NOT_FOUND","接口不存在",null);}
 @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class) ResponseEntity<V2Api.Failure> noResource(Exception e){return failure(HttpStatus.NOT_FOUND,"NOT_FOUND","接口不存在",null);}
 @ExceptionHandler(HttpRequestMethodNotSupportedException.class) ResponseEntity<V2Api.Failure> method(Exception e){return failure(HttpStatus.METHOD_NOT_ALLOWED,"METHOD_NOT_ALLOWED","请求方法不支持",null);}
 @ExceptionHandler(Exception.class) ResponseEntity<V2Api.Failure> unexpected(Exception e){log.error("Unhandled v2 error requestId={}",RequestIds.current(),e);return failure(HttpStatus.INTERNAL_SERVER_ERROR,"INTERNAL_ERROR","服务暂时不可用",null);}
 private ResponseEntity<V2Api.Failure> failure(HttpStatus s,String c,String m,Object d){return ResponseEntity.status(s).body(new V2Api.Failure(new V2Api.Error(c,m,d)));}
}
