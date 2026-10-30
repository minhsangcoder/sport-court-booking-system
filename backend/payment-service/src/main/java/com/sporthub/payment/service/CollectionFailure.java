package com.sporthub.payment.service;

/** A bank collection could not be applied safely; the code is kept for review and reporting. */
public final class CollectionFailure extends RuntimeException {
  private final String code;

  public CollectionFailure(String code) {
    super("Kênh nhận tiền chưa thể xử lý yêu cầu (" + code + ").");
    this.code = code;
  }

  public String code() {
    return code;
  }
}
