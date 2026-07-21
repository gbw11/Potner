package com.potner.user.application;

import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.security.AuthenticatedUser;
import com.potner.user.domain.AppUser;
import com.potner.user.domain.AppUserRepository;
import com.potner.user.dto.UserMeResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UserService {

    private final AppUserRepository appUserRepository;

    public UserService(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    public UserMeResponse getMe(AuthenticatedUser principal) {
        AppUser user = appUserRepository.findById(principal.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (!user.isActive()) {
            throw new BusinessException(ErrorCode.ACCOUNT_INACTIVE);
        }
        return new UserMeResponse(user.getId(), user.getEmail(), user.getNickname());
    }
}
