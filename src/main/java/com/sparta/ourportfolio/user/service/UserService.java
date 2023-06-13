package com.sparta.ourportfolio.user.service;

import com.sparta.ourportfolio.common.dto.ResponseDto;
import com.sparta.ourportfolio.common.exception.GlobalException;
import com.sparta.ourportfolio.common.jwt.JwtTokenDto;
import com.sparta.ourportfolio.common.jwt.JwtUtil;
import com.sparta.ourportfolio.common.jwt.refreshToken.RefreshTokenRepository;
import com.sparta.ourportfolio.common.utils.S3Service;
import com.sparta.ourportfolio.user.dto.*;
import com.sparta.ourportfolio.user.entity.User;
import com.sparta.ourportfolio.user.repository.UserRepository;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.thymeleaf.util.StringUtils;

import java.io.IOException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.sparta.ourportfolio.common.exception.ExceptionEnum.*;

@Service
@RequiredArgsConstructor
@Transactional
public class UserService {

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenRepository refreshTokenRepository;
    private final S3Service s3Service;

    private static final String EMAIL_PATTERN = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$";
    private static final String PASSWORD_PATTERN = "^(?=.*[a-zA-Z])(?=.*\\d)[a-zA-Z\\d]{6,}$";
    private static final String NICKNAME_PATTERN = "^[a-zA-Z가-힣0-9]{1,10}$";

    //회원가입
    public ResponseDto<UserDto> signup(SignupRequestDto signupRequestDto) {
        validateEmail(signupRequestDto.getEmail());
        validatePassword(signupRequestDto.getPassword());
        validateNickname(signupRequestDto.getNickname());

        String password = passwordEncoder.encode(signupRequestDto.getPassword());

        Optional<User> findNicknameByEmail = userRepository.findByNickname(signupRequestDto.getNickname());
        if (findNicknameByEmail.isPresent()) {
            throw new GlobalException(DUPLICATED_NICK_NAME);
        }

        User user = new User(password, signupRequestDto);
        userRepository.save(user);
        return ResponseDto.setSuccess(HttpStatus.OK, "회원가입 성공!", null);
    }

    //로그인
    public ResponseDto<UserDto> login(LoginRequestDto loginRequestDto, HttpServletResponse response) {
        String email = loginRequestDto.getEmail();
        String password = loginRequestDto.getPassword();

        //이메일, 비밀번호 패턴 검사
        validateEmail(email);
        validatePassword(password);

        User user = userRepository.findByEmail(email).orElseThrow(
                () -> new GlobalException(NOT_FOUND_USER));

        if (user.isDeleted()) {
            throw new GlobalException(USER_IS_DELETED);
        }
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new GlobalException(BAD_PASSWORD);
        }

        jwtUtil.createAndSetToken(response, user.getEmail(), user.getId());
        return ResponseDto.setSuccess(HttpStatus.OK, "로그인 성공!", null);
    }

    // 회원 조회
    public ResponseDto<UserDto> getUser(Long id) {
        User user = userRepository.findById(id).orElseThrow(
                () -> new GlobalException(NOT_FOUND_USER));
        UserDto userDto = new UserDto(user.getId(), user.getEmail(), user.getNickname(),
                user.getProfileImage(), user.getKakaoId(), user.getNaverId());
        return ResponseDto.setSuccess(HttpStatus.OK, "회원 조회 성공!", userDto);
    }

    // 회원 정보 수정
    public ResponseDto<UserDto> updateUser(Long id, UpdateUserRequestDto updateUserRequestDto,
                                           MultipartFile image, User user) throws IOException {
        if (!StringUtils.equals(id, user.getId())) {
            throw new GlobalException(UNAUTHORIZED);
        }

        if (!StringUtils.equals(updateUserRequestDto.getNickname(), user.getNickname()) && userRepository.existsByNickname(updateUserRequestDto.getNickname())) {
            throw new GlobalException(DUPLICATED_NICK_NAME);
        }
        validateNickname(updateUserRequestDto.getNickname());

        String imageUrl = null;

        if (image == null) {
            imageUrl = user.getProfileImage();
        }
        if (image != null) {
            imageUrl = s3Service.uploadFile(image);
        }

        user.updateUser(updateUserRequestDto, imageUrl);
        userRepository.save(user);

        return ResponseDto.setSuccess(HttpStatus.OK, "회원 정보 수정 성공!", null);
    }

    // 비밀번호 변경
    public ResponseDto<UserDto> updatePassword(Long id, UpdatePasswordRequestDto updatePasswordRequestDto, User user) {
        if (!StringUtils.equals(id, user.getId())) {
            throw new GlobalException(UNAUTHORIZED);
        }

        if (!passwordEncoder.matches(updatePasswordRequestDto.getOldPassword(), user.getPassword())) {
            throw new GlobalException(PRESENT_PASSWORD);
        }

        if (!updatePasswordRequestDto.getNewPassword().equals(updatePasswordRequestDto.getCheckNewPassword())) {
            throw new GlobalException(COINCIDE_PASSWORD);
        }

        validatePassword(updatePasswordRequestDto.getNewPassword()); // 비밀번호 패턴 검사

        user.updatePassword(passwordEncoder.encode(updatePasswordRequestDto.getNewPassword()));
        userRepository.save(user);
        return ResponseDto.setSuccess(HttpStatus.OK, "비밀번호 변경 성공!", null);
    }

    // 회원 탈퇴(soft, default)
    public ResponseDto<UserDto> deleteUser(Long id, User user) {
        userRepository.findById(id).orElseThrow(
                () -> new GlobalException(NOT_FOUND_USER));

        user.deleteUser(); // Soft delete 수행
        userRepository.save(user);

        return ResponseDto.setSuccess(HttpStatus.OK, "회원 탈퇴 성공!", null);
    }

    // 회원 탈퇴(hard delete)
    public ResponseDto<UserDto> deleteUserHard(Long id, User user) {
        userRepository.deleteById(user.getId());
        return ResponseDto.setSuccess(HttpStatus.OK, "영구 삭제", null);
    }

    private void setHeader(HttpServletResponse response, JwtTokenDto tokenDto) {
        response.addHeader(JwtUtil.ACCESS_TOKEN, tokenDto.getAccessToken());
        response.addHeader(JwtUtil.REFRESH_TOKEN, tokenDto.getRefreshToken());
    }

    //이메일 중복 검사
    public ResponseDto<Boolean> checkEmail(String email) {
        validateEmail(email);

        boolean exists = userRepository.existsByEmail(email);
        if (exists) {
            throw new GlobalException(DUPLICATED_EMAIL);
        }
        return ResponseDto.setSuccess(HttpStatus.OK, "이메일이 중복되지 않습니다.");
    }

    //이메일 패턴 검사
    private void validateEmail(String email) {
        Pattern pattern = Pattern.compile(EMAIL_PATTERN);
        Matcher matcher = pattern.matcher(email);
        if (!matcher.matches()) {
            throw new GlobalException(EMAIL_REGEX);
        }
    }

    //비밀번호 패턴 검사
    private void validatePassword(String password) {
        Pattern pattern = Pattern.compile(PASSWORD_PATTERN);
        Matcher matcher = pattern.matcher(password);
        if (!matcher.matches()) {
            throw new GlobalException(PASSWORD_REGEX);
        }
    }

    //닉네임 패턴 검사
    private void validateNickname(String nickname) {
        Pattern pattern = Pattern.compile(NICKNAME_PATTERN);
        Matcher matcher = pattern.matcher(nickname);
        if (!matcher.matches()) {
            throw new GlobalException(NICKNAME_REGEX);
        }
    }

    //토큰 재발급
    public ResponseDto<UserDto> reissueToken(String refreshToken, HttpServletResponse response) {
        jwtUtil.refreshTokenValid(refreshToken);
        String email = jwtUtil.getUserInfoFromToken(refreshToken);
        User user = userRepository.findByEmail(email).get();
        String newAccessToken = jwtUtil.createToken(email, "Access", user.getId());
        response.setHeader("ACCESSTOKEN", newAccessToken);
        return ResponseDto.setSuccess(HttpStatus.OK,"토큰 재발급 성공!");
    }

//    public void refreshToken(HttpServletRequest request, HttpServletResponse response) {
//        String refreshTokenValue = request.getHeader("refreshToken");
//        jwtUtil.validateToken(refreshTokenValue);
//        RefreshToken refreshToken = refreshTokenRepository.findByValue(refreshTokenValue).orElse(null);
//        // 해당유저의 리프레시 토큰이 DB에 없는 경우 예외처리
//        if (refreshToken == null) {
//            throw new GlobalException(EXPIRED_JWT_TOKEN);
//        }
//
//        // 리프레시 토큰값에 대한 유저가 있는지 체크하고 없으면 예외처리
//        Optional<User> user = userRepository.findById(refreshToken.getId());
//        if(!user.isPresent()){
//            throw new GlobalException(NOT_FOUND_USER);
//        }
//        User requestingUser = user.get();
//
//        // 리프레시 토큰값이 유효한지 체크하고 아니면 예외처리
//        if (!Objects.equals(refreshToken.getValue(), refreshTokenValue)) {
//            jwtUtil.deleteRefreshToken(requestingUser);
//            throw new GlobalException(INVALID_JWT_TOKEN);
//        }
//
//        // 헤더에 저장된 엑세스토큰 만료기간을 가져오고 없으면 예외처리
//        String expireTime = request.getHeader("Access-Token-Expire-Time");
//        if (expireTime == null) throw new GlobalException(EXPIRED_JWT_TOKEN);
//
//        long accessTokenExpire = Long.parseLong(expireTime);
//        long now = new Date().getTime();
//
//        // 엑세스토큰만료 시간이 현재시간을 초과하는지 체크
//        if (now >= accessTokenExpire) {
//            // 엑세스토큰이 만료되고 리프레시 토큰도 만료됬을 시 DB에서 리프레시 토큰 삭제 후 예외처리
//            if (now >= refreshToken.getExpirationDate().getTime()) {
//                jwtUtil.deleteRefreshToken(requestingUser);
//                throw new GlobalException(INVALID_JWT_TOKEN);
//            } else {
//                // 엑세스토큰이 만료되고 리프레시토큰이 만료되지 않았을시 엑세스토큰 재발급
//                TokenDto tokenDto = tokenProvider.generateAccessTokenDto(requestingUser, requestingUser.getRole());
//                validation.accessTokenToHeaders(tokenDto, response);
//            }
//            // 엑세스 토큰이 만료되지 않았을 시 엑세스 토큰 재발급
//        } else {
//            TokenDto tokenDto = tokenProvider.generateAccessTokenDto(requestingUser, requestingUser.getRole());
//            validation.accessTokenToHeaders(tokenDto, response);
//        }
//    }
}