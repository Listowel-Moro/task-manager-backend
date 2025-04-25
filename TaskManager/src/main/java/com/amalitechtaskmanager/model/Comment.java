package com.amalitechtaskmanager.model;


import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.joda.time.LocalDate;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
public class Comment {
    private String commentId;
    private String content;
    private String taskId;
    private String userId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
