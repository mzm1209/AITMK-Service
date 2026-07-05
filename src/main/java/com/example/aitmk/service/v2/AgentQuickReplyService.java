package com.example.aitmk.service.v2;

import com.example.aitmk.model.api.v2.V2Api.QuickReplyListView;
import com.example.aitmk.model.api.v2.V2Api.QuickReplyRequest;
import com.example.aitmk.model.api.v2.V2Api.QuickReplyView;
import com.example.aitmk.model.api.v2.V2Exception;
import com.example.aitmk.model.entity.AgentQuickReplyEntity;
import com.example.aitmk.repository.AgentQuickReplyRepository;
import com.example.aitmk.security.auth.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class AgentQuickReplyService {

    private static final int DEFAULT_SIZE = 100;
    private static final int MAX_SIZE = 200;
    private static final int TITLE_MAX = 80;
    private static final int CONTENT_MAX = 4096;
    private static final int CATEGORY_MAX = 40;

    private final AgentQuickReplyRepository repository;

    @Transactional(readOnly = true)
    public QuickReplyListView list(AuthenticatedUser user, String keyword, String category, Integer size) {
        String agentRowId = requireAgentRowId(user);
        var items = repository.searchActive(
                        agentRowId,
                        blankToNull(keyword),
                        blankToNull(category),
                        PageRequest.of(0, boundedSize(size)))
                .stream()
                .map(this::view)
                .toList();
        return new QuickReplyListView(items);
    }

    @Transactional
    public QuickReplyView create(AuthenticatedUser user, QuickReplyRequest request) {
        String agentRowId = requireAgentRowId(user);
        NormalizedRequest normalized = validate(request);
        AgentQuickReplyEntity entity = new AgentQuickReplyEntity();
        entity.setAgentRowId(agentRowId);
        apply(entity, normalized);
        return view(repository.save(entity));
    }

    @Transactional
    public QuickReplyView update(Long id, AuthenticatedUser user, QuickReplyRequest request) {
        AgentQuickReplyEntity entity = findOwned(id, user);
        apply(entity, validate(request));
        return view(repository.save(entity));
    }

    @Transactional
    public void delete(Long id, AuthenticatedUser user) {
        AgentQuickReplyEntity entity = findOwned(id, user);
        entity.setDeletedAt(Instant.now());
        repository.save(entity);
    }

    private AgentQuickReplyEntity findOwned(Long id, AuthenticatedUser user) {
        if (id == null) {
            throw invalid("id is required");
        }
        String agentRowId = requireAgentRowId(user);
        return repository.findByIdAndAgentRowIdAndDeletedAtIsNull(id, agentRowId)
                .orElseThrow(() -> new V2Exception(HttpStatus.NOT_FOUND, "QUICK_REPLY_NOT_FOUND", "话术不存在"));
    }

    private void apply(AgentQuickReplyEntity entity, NormalizedRequest request) {
        entity.setTitle(request.title());
        entity.setContent(request.content());
        entity.setCategory(request.category());
        entity.setSortOrder(request.sortOrder());
        entity.setEnabled(true);
    }

    private QuickReplyView view(AgentQuickReplyEntity entity) {
        return new QuickReplyView(
                entity.getId().toString(),
                entity.getTitle(),
                entity.getContent(),
                entity.getCategory(),
                entity.getSortOrder(),
                entity.getUpdatedAt());
    }

    private NormalizedRequest validate(QuickReplyRequest request) {
        if (request == null) {
            throw invalid("request body is required");
        }
        String title = requiredTrim(request.title(), "title");
        String content = requiredTrim(request.content(), "content");
        String category = blankToNull(request.category());
        if (title.length() > TITLE_MAX) {
            throw invalid("title exceeds 80 characters");
        }
        if (content.length() > CONTENT_MAX) {
            throw invalid("content exceeds 4096 characters");
        }
        if (category != null && category.length() > CATEGORY_MAX) {
            throw invalid("category exceeds 40 characters");
        }
        return new NormalizedRequest(title, content, category, request.sortOrder() == null ? 0 : request.sortOrder());
    }

    private String requireAgentRowId(AuthenticatedUser user) {
        if (user == null || !StringUtils.hasText(user.getAccountRowId())) {
            throw new V2Exception(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "未登录或 token 失效");
        }
        return user.getAccountRowId().trim();
    }

    private String requiredTrim(String value, String field) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            throw invalid(field + " is required");
        }
        return normalized;
    }

    private String blankToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private int boundedSize(Integer requested) {
        if (requested == null) {
            return DEFAULT_SIZE;
        }
        if (requested < 1) {
            return DEFAULT_SIZE;
        }
        return Math.min(requested, MAX_SIZE);
    }

    private V2Exception invalid(String message) {
        return new V2Exception(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", message);
    }

    private record NormalizedRequest(String title, String content, String category, int sortOrder) {}
}
