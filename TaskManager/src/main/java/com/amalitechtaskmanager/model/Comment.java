package com.amalitechtaskmanager.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class Comment {

    @JsonProperty("commentId")
    private String commentId;

    @JsonProperty("content")
    private String content;

    @JsonProperty("taskId")
    private String taskId;

    @JsonProperty("userId")
    private String userId;

    @JsonProperty("createdAt")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonProperty("updatedAt")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;

    /**
     * Custom constructor for creating a comment with essential fields.
     * createdAt and updatedAt are automatically initialized to now().
     */
    public Comment(String content, String taskId, String userId) {
        this.commentId = null; // to be generated
        this.content = content;
        this.taskId = taskId;
        this.userId = userId;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void updateContent(String newContent) {
        this.content = newContent;
        this.updatedAt = LocalDateTime.now();
    }
}
