package com.amalitechtaskmanager.model;

import com.amalitechtaskmanager.exception.CannotSetCompletedAtException;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Task {


    @JsonProperty("taskId")
    private String taskId;

    @JsonProperty("name")
    private String name;

    @JsonProperty("description")
    private String description;

    @JsonProperty("status")
    private TaskStatus status = TaskStatus.OPEN;

    @JsonProperty("deadline")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime deadline;

    @JsonProperty("createdAt")
    @JsonFormat (shape = JsonFormat.Shape.STRING,pattern ="yyyy-MM-dd'T'HH:mm:ss" )
    private LocalDateTime createdAt;


    @JsonProperty("completed_at")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime completedAt;

    @JsonProperty("expired_at")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime expiredAt;

    @JsonProperty("userId")
    private String userId;

    @JsonProperty("responsibility")
    private String responsibility;

    @JsonProperty("user_comment")
    private String userComment;

    public Task(String taskId, String name, String description, TaskStatus status,
                LocalDateTime deadline,
                LocalDateTime completedAt, String userComment ,String  userId) {

        this.taskId = taskId;
        this.name = name;
        this.description = description;
        this.status = status;
        this.deadline = deadline;
        this.userComment = userComment;
        this.userId= userId;

        // Validate that completedAt is only set if status == COMPLETED
        if (status == TaskStatus.COMPLETED) {
            this.completedAt = completedAt;
            this.expiredAt = null;
        } else if (status == TaskStatus.EXPIRED) {
            this.expiredAt = LocalDateTime.now();
            this.completedAt = null;
        } else {
            this.completedAt = null;
            this.expiredAt = null;
        }
    }

    /*
      Rose  uses this constructor
     */
    public Task(String taskId, String taskName, String description, String status, String deadlineStr, String userId) {
        this.taskId = taskId;
        this.name = taskName;
        this.description = description;
        this.userId = userId;

        try {
            this.deadline = LocalDateTime.parse(deadlineStr);
        } catch (Exception e) {
            // Handle parsing error
        }

        if ("EXPIRED".equalsIgnoreCase(status)) {
            this.status = TaskStatus.EXPIRED;
            this.expiredAt = LocalDateTime.now();
        } else if ("COMPLETED".equalsIgnoreCase(status)) {
            this.status = TaskStatus.COMPLETED;
            this.completedAt = LocalDateTime.now();
        } else {
            this.status = TaskStatus.OPEN;
        }
    }


    public void setCompletedAt(LocalDateTime completedAt) {
        if (this.status == TaskStatus.COMPLETED) {
            this.completedAt = completedAt;
        } else {
            throw new CannotSetCompletedAtException("Cannot set completedAt unless status is COMPLETED");
        }
    }

    public void setExpiredAt(LocalDateTime expiredAt) {
        if (this.status == TaskStatus.EXPIRED) {
            this.expiredAt = expiredAt;
        } else {
            throw new IllegalStateException("Cannot set expiredAt unless status is EXPIRED");
        }
    }

    /**
     * Marks a task as expired and sets the expiredAt timestamp
     */
    public void markAsExpired() {
        this.status = TaskStatus.EXPIRED;
        this.expiredAt = LocalDateTime.now();
    }

}
