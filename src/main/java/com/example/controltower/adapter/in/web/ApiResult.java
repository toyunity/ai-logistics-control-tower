package com.example.controltower.adapter.in.web;

/** 명령, 정비, 오류 응답의 고정 본문이다. message는 서버가 정한 문구만 담는다. */
record ApiResult(String status, String message) {}
