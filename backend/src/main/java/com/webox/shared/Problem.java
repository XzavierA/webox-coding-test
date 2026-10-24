package com.webox.shared;

import java.util.List;
import java.util.Map;

public record Problem(String type, String title, int status, String detail, String code,
                      String correlationId, List<ApiException.FieldError> fieldErrors,
                      Map<String, Object> details) { }
