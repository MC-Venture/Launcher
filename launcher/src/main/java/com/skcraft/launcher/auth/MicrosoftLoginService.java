package com.skcraft.launcher.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.skcraft.launcher.auth.microsoft.MicrosoftWebAuthorizer;
import com.skcraft.launcher.auth.microsoft.MinecraftServicesAuthorizer;
import com.skcraft.launcher.auth.microsoft.XboxTokenAuthorizer;
import com.skcraft.launcher.auth.microsoft.model.McAuthResponse;
import com.skcraft.launcher.auth.microsoft.model.McProfileResponse;
import com.skcraft.launcher.auth.microsoft.model.TokenResponse;
import com.skcraft.launcher.auth.microsoft.model.XboxAuthorization;
import com.skcraft.launcher.util.HttpRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.Map;
import java.util.function.Consumer;

import static com.skcraft.launcher.util.HttpRequest.url;

@RequiredArgsConstructor
public class MicrosoftLoginService implements LoginService {
	private static final URL MS_TOKEN_URL = url("https://login.live.com/oauth20_token.srf");

	private final String clientId;

	public Session login() throws IOException, InterruptedException, AuthenticationException {
		MicrosoftWebAuthorizer authorizer = new MicrosoftWebAuthorizer(clientId);
		String code = authorizer.authorize();

		TokenResponse response = exchangeToken(form -> {
			form.add("grant_type", "authorization_code");
			form.add("redirect_uri", authorizer.getRedirectUri());
			form.add("code", code);
		});

		Profile session = performLogin(response.getAccessToken());
		session.setRefreshToken(response.getRefreshToken());

		return session;
	}

	@Override
	public Session restore(SavedSession savedSession)
			throws IOException, InterruptedException, AuthenticationException {
		TokenResponse response = exchangeToken(form -> {
			form.add("grant_type", "refresh_token");
			form.add("refresh_token", savedSession.getRefreshToken());
		});

		Profile session = performLogin(response.getAccessToken());
		session.setRefreshToken(response.getRefreshToken());

		return session;
	}

	private TokenResponse exchangeToken(Consumer<HttpRequest.Form> formConsumer)
			throws IOException, InterruptedException, AuthenticationException {
		HttpRequest.Form form = HttpRequest.Form.form();
		form.add("client_id", clientId);
		formConsumer.accept(form);

		HttpRequest request = HttpRequest.post(MS_TOKEN_URL)
				.bodyForm(form)
				.execute();

		if (request.getResponseCode() == 200) {
			return request.returnContent().asJson(TokenResponse.class);
		} else {
			TokenError error = request.returnContent().asJson(TokenError.class);

			throw new AuthenticationException(error.errorDescription, error.errorDescription);
		}
	}

	private Profile performLogin(String microsoftToken)
			throws IOException, InterruptedException, AuthenticationException {
		XboxAuthorization xboxAuthorization = XboxTokenAuthorizer.authorizeWithXbox(microsoftToken);
		McAuthResponse auth = MinecraftServicesAuthorizer.authorizeWithMinecraft(xboxAuthorization);
		McProfileResponse profile = MinecraftServicesAuthorizer.getUserProfile(auth);

		Profile session = new Profile(auth, profile);
		session.setAvatarImage(VisageSkinService.fetchSkinHead(profile.getUuid()));

		return session;
	}

	@Data
	public static class Profile implements Session {
		private final McAuthResponse auth;
		private final McProfileResponse profile;
		private final Map<String, String> userProperties = Collections.emptyMap();
		private String refreshToken;
		private String avatarImage;

		@Override
		public String getUuid() {
			return profile.getUuid();
		}

		@Override
		public String getName() {
			return profile.getName();
		}

		@Override
		public String getAccessToken() {
			return auth.getAccessToken();
		}

		@Override
		public String getSessionToken() {
			return String.format("token:%s:%s", getAccessToken(), getUuid());
		}

		@Override
		public UserType getUserType() {
			return UserType.MICROSOFT;
		}

		@Override
		public boolean isOnline() {
			return true;
		}

		@Override
		public SavedSession toSavedSession() {
			SavedSession savedSession = new SavedSession();

			savedSession.setType(getUserType());
			savedSession.setUsername(getName());
			savedSession.setUuid(getUuid());
			savedSession.setAccessToken(getAccessToken());
			savedSession.setRefreshToken(getRefreshToken());
			savedSession.setAvatarImage(getAvatarImage());

			return savedSession;
		}
	}

	@Data
	@JsonNaming(PropertyNamingStrategy.LowerCaseWithUnderscoresStrategy.class)
	@JsonIgnoreProperties(ignoreUnknown = true)
	private static class TokenError {
		private String error;
		private String errorDescription;
	}
}