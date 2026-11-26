package com.rssolplan.edu.domain.roster;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class TeachingDirectoryController {
    private final TeachingDirectoryService directory;

    @GetMapping("/api/v2/terms/{termId}/teaching-directory")
    public TeachingDirectoryService.Directory read(@AuthenticationPrincipal Long userId,
                                                  @PathVariable long termId) {
        return directory.read(userId, termId);
    }
}
