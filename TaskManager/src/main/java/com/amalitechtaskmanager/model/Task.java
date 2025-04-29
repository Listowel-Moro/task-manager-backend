package com.amalitechtaskmanager.model;

import com.amalitechtaskmanager.exception.CannotSetCompletedAtException;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.time.LocalDateTime;

@ToString
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
        } else {
            this.completedAt = null;
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
        } else if ("COMPLETED".equalsIgnoreCase(status)) {
            this.status = TaskStatus.COMPLETED;
            this.completedAt = LocalDateTime.now();
        } else {
            this.status = TaskStatus.OPEN;
        }
    }


//    public void setCompletedAt(LocalDateTime completedAt) {
//        if (completedAt != null && this.status != TaskStatus.COMPLETED) {
//            throw new CannotSetCompletedAtException("Cannot set completedAt unless status is COMPLETED");
//        }
//        this.completedAt = completedAt;
//    }

    /**
     * Marks a task as expired
     */
    public void markAsExpired() {
        this.status = TaskStatus.EXPIRED;
    }

}
