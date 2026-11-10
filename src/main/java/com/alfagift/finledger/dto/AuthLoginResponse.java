package com.alfagift.finledger.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response login berisi JWT dan role pengguna.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthLoginResponse {

    private String accessToken;
    private String tokenType;
    private long expiresInSeconds;
    private String username;
    private List<String> roles;
}
