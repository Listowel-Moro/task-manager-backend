package com.amalitechtaskmanager.model;

import com.amalitechtaskmanager.exception.CannotSetCompletedAtException;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
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

    @JsonProperty("responsibility")
    private String responsibility;

    @JsonProperty("completed_at")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime completedAt;

    @JsonProperty("userId")
    private String userId;

    @JsonProperty("user_comment")
    private String userComment;

    public Task(String taskId, String name, String description, TaskStatus status,
                LocalDateTime deadline, String responsibility,
                LocalDateTime completedAt, String userComment ,String  userId) {

        this.taskId = taskId;
        this.name = name;
        this.description = description;
        this.status = status;
        this.deadline = deadline;
        this.responsibility = responsibility;
        this.userComment = userComment;
        this.userId= userId;

        // Validate that completedAt is only set if status == COMPLETED
        if (status == TaskStatus.COMPLETED) {
            this.completedAt = completedAt;
        } else {
            this.completedAt = null;
        }
    }


    public void setCompletedAt(LocalDateTime completedAt) {
        if (this.status == TaskStatus.COMPLETED) {
            this.completedAt = completedAt;
        } else {
            throw  new CannotSetCompletedAtException("Cannot set completedAt unless status is COMPLETED");
        }
    }
}
