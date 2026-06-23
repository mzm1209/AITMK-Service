package com.example.aitmk.model.api.v2;
import org.springframework.http.HttpStatus;
import lombok.Getter;
@Getter public class V2Exception extends RuntimeException {private final HttpStatus status;private final String code;private final Object details;
 public V2Exception(HttpStatus status,String code,String message){this(status,code,message,null);} public V2Exception(HttpStatus s,String c,String m,Object d){super(m);status=s;code=c;details=d;}}
