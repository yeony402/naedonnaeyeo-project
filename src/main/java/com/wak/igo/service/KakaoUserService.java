package com.wak.igo.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.wak.igo.domain.Member;
import com.wak.igo.domain.UserDetailsImpl;
import com.wak.igo.jwt.TokenProvider;
import com.wak.igo.repository.MemberRepository;
import com.wak.igo.request.TokenDto;
import com.wak.igo.request.MemberInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import javax.servlet.http.HttpServletResponse;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
@Service
public class KakaoUserService {
    private final PasswordEncoder passwordEncoder;
    private final MemberRepository memberRepository;
    private final TokenProvider tokenProvider;

    public MemberInfo kakaologin(String code, HttpServletResponse response) throws JsonProcessingException {
        // 1. "인가 코드"로 전체 response 요청
        String accessToken = getAccessToken(code);

        // 2. response에 access token으로 카카오 api 호출
        MemberInfo kakaoUserInfo = getkakaoUserInfo(accessToken);

        // 3. 필요시에 회원가입
        Member kakaoUser = registerKakaoUserIfNeeded(kakaoUserInfo);

        // 4. 강제 로그인 처리
        forceLogin(kakaoUser);

        // 5. response Header에 JWT 토큰 추가
        kakaoUsersAuthorizationInput(kakaoUser, response);
        return kakaoUserInfo;

    }

    private String getAccessToken(String code) throws JsonProcessingException {
        // HTTP Header 생성
        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-type", "application/x-www-form-urlencoded;charset=utf-8");

        // HTTP Body 생성
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add("client_id", "fdb42734830cbb186c8221bf3acdd6c6");
//        body.add("client_id", "3d365192ea8ab4f32c7f9c1d7c5688e1");
        body.add("client_secret", "FuvfQecT3uPmfM3wlzF5VxRJU7Iz654F");
        body.add("redirect_url", "http://localhost:8080/kakao/callback");
//        body.add("redirect_uri", "http://localhost:3000/kakaoloading");
        body.add("code", code);

        // HTTP 요청 보내기
        HttpEntity<MultiValueMap<String, String>> kakaoTokenRequest =
                new HttpEntity<>(body, headers);
        RestTemplate rt = new RestTemplate();
        ResponseEntity<String> response = rt.exchange(
                "https://kauth.kakao.com/oauth/token",
                HttpMethod.POST,
                kakaoTokenRequest,
                String.class
        );

        // HTTP 응답 (JSON) -> 액세스 토큰 파싱
        String responseBody = response.getBody();
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode jsonNode = objectMapper.readTree(responseBody);
        return jsonNode.get("access_token").asText();
    }

    private MemberInfo getkakaoUserInfo(String accessToken) throws JsonProcessingException {
        // HTTP Header 생성
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", "Bearer " + accessToken);
        headers.add("Content-type", "application/x-www-form-urlencoded;charset=utf-8");

        // HTTP 요청 보내기
        HttpEntity<MultiValueMap<String, String>> kakaoUserInfoRequest = new HttpEntity<>(headers);
        RestTemplate restTemplate = new RestTemplate();
        ResponseEntity<String> response = restTemplate.exchange(
                "https://kapi.kakao.com/v2/user/me",
                HttpMethod.POST,
                kakaoUserInfoRequest,
                String.class
        );

        String responseBody = response.getBody();
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode jsonNode = objectMapper.readTree(responseBody);
        String id = jsonNode.get("id").asText();
        String nickname = jsonNode.get("properties").get("nickname").asText();
        log.info("카카오 사용자 정보: " + id + ", " + nickname);
        return new MemberInfo(id, nickname);
    }

    private Member registerKakaoUserIfNeeded(MemberInfo kakaoUserInfo) {
        // DB 에 중복된 Kakao Id 가 있는지 확인
        String kakaoId = kakaoUserInfo.getId();
//        String kakaoId = kakaoUserInfo.getMemberId();
        Member kakaoUser = memberRepository.findByMemberid(kakaoId)
                .orElse(null);
        if (kakaoUser == null) {
            // 회원가입
            // nickname: kakao nickname
            String nickname = kakaoUserInfo.getNickname();

            // password: random UUID
            String password = UUID.randomUUID().toString();
            String encodedPassword = passwordEncoder.encode(password);

            kakaoUser = Member.builder()
                    .nickname(nickname)
                    .password(encodedPassword)
                    .memberid(kakaoId)
                    .build();
            memberRepository.save(kakaoUser);
            log.info(nickname + "회원가입이 완료되었습니다.");
        }
        return kakaoUser;
    }

    private void forceLogin(Member kakaoUser) {
        UserDetails userDetails = new UserDetailsImpl(kakaoUser);
        Authentication authentication = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private void kakaoUsersAuthorizationInput(Member kakaouser, HttpServletResponse response) {
        // response header에 token 추가
        TokenDto token = tokenProvider.generateTokenDto(kakaouser);
        response.addHeader("Authorization", "BEARER" + " " + token.getAccessToken());
        response.addHeader("RefreshToken", token.getRefreshToken());
        response.addHeader("Access-Token-Expire-Time", token.getAccessTokenExpiresIn().toString());
    }
}