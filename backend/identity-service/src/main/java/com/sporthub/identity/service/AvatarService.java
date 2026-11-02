package com.sporthub.identity.service;

import com.sporthub.common.upload.UploadValidation;
import com.sporthub.identity.domain.AccountStatus;
import com.sporthub.identity.domain.User;
import com.sporthub.identity.exception.IdentityException;
import com.sporthub.identity.repository.UserRepository;
import com.sporthub.identity.web.dto.UserProfileView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;

/** Uploaded profile photos. Every photo is re-encoded, so EXIF data such as GPS never becomes public. */
@Service
@RequiredArgsConstructor
public class AvatarService {
    public static final String URL_PREFIX = "/api/v1/users/avatars/";
    private static final int MAX_SIDE = 512;

    private final UserRepository users;
    private final UserViewMapper mapper;
    private final UserSecurityLock securityLock;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    @Transactional
    public UserProfileView replace(UUID userId, MultipartFile file, RequestMetadata metadata) {
        byte[] content = squareJpeg(file);
        User user = activeUser(userId);
        UUID id = UUID.randomUUID();
        jdbc.update("DELETE FROM user_avatars WHERE user_id=?", userId);
        jdbc.update("INSERT INTO user_avatars(id,user_id,content) VALUES(?,?,?)", id, userId, content);
        user.getProfile().setAvatarUrl(URL_PREFIX + id);
        audit.record(userId, "AVATAR_UPLOADED", "USER", userId, metadata);
        return mapper.toView(user);
    }

    @Transactional
    public UserProfileView remove(UUID userId, RequestMetadata metadata) {
        User user = activeUser(userId);
        jdbc.update("DELETE FROM user_avatars WHERE user_id=?", userId);
        user.getProfile().setAvatarUrl(null);
        audit.record(userId, "AVATAR_REMOVED", "USER", userId, metadata);
        return mapper.toView(user);
    }

    @Transactional(readOnly = true)
    public byte[] content(UUID id) {
        return jdbc.query("SELECT content FROM user_avatars WHERE id=?", (rs, n) -> rs.getBytes(1), id).stream().findFirst()
                .orElseThrow(() -> new IdentityException(HttpStatus.NOT_FOUND, "IDENTITY-AVATAR-NOT-FOUND", "Avatar was not found"));
    }

    private User activeUser(UUID userId) {
        User user = securityLock.current(users.findById(userId).orElseThrow(() ->
                new IdentityException(HttpStatus.NOT_FOUND, "IDENTITY-USER-NOT-FOUND", "User was not found")));
        if (user.getStatus() != AccountStatus.ACTIVE)
            throw new IdentityException(HttpStatus.FORBIDDEN, "IDENTITY-ACCOUNT-INACTIVE", "Account is not active");
        return user;
    }

    private byte[] squareJpeg(MultipartFile file) {
        try {
            UploadValidation.image(file);
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(file.getBytes()));
            if (source == null) throw new IllegalArgumentException("Invalid image content");
            int crop = Math.min(source.getWidth(), source.getHeight());
            int side = Math.min(crop, MAX_SIDE);
            int left = (source.getWidth() - crop) / 2, top = (source.getHeight() - crop) / 2;
            BufferedImage square = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB);
            var graphics = square.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, side, side, left, top, left + crop, top + crop, Color.WHITE, null);
            graphics.dispose();
            var output = new ByteArrayOutputStream();
            ImageIO.write(square, "jpeg", output);
            return output.toByteArray();
        } catch (IOException | IllegalArgumentException ex) {
            throw new IdentityException(HttpStatus.BAD_REQUEST, "IDENTITY-AVATAR-INVALID", "Avatar must be a JPEG or PNG image");
        }
    }
}
