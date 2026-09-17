package ch.it4user.fintube.persistence.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/** Restartable background media job. */
@Entity
@Table(name = "jobs")
public class JobEntity {
    @Id
    @Column(length = 255, nullable = false)
    private String id;

    @Column(name = "video_id", length = 255, nullable = false)
    private String videoId;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(nullable = false)
    private int priority;

    @Column(name = "created_at", nullable = false, length = 64)
    private String createdAt;

    @Lob
    private String error;

    @Column(nullable = false, length = 32)
    private String type;

    @Column(name = "requested_fragment", length = 255)
    private String requestedFragment;

    @Column(name = "completed_fragments", nullable = false)
    private int completedFragments;

    @Column(name = "total_fragments", nullable = false)
    private int totalFragments;

    @Column(name = "cancel_requested", nullable = false)
    private int cancelRequested;

    @Column(name = "started_at", length = 64)
    private String startedAt;

    @Column(name = "updated_at", length = 64)
    private String updatedAt;

    @Column(name = "completed_at", length = 64)
    private String completedAt;

    protected JobEntity() {
    }

    public JobEntity(String id, String videoId, String status, int priority, String createdAt, String error,
                     String type, String requestedFragment, int completedFragments, int totalFragments,
                     int cancelRequested, String startedAt, String updatedAt, String completedAt) {
        this.id = id;
        this.videoId = videoId;
        this.status = status;
        this.priority = priority;
        this.createdAt = createdAt;
        this.error = error;
        this.type = type;
        this.requestedFragment = requestedFragment;
        this.completedFragments = completedFragments;
        this.totalFragments = totalFragments;
        this.cancelRequested = cancelRequested;
        this.startedAt = startedAt;
        this.updatedAt = updatedAt;
        this.completedAt = completedAt;
    }

    public String getId() {
        return id;
    }

    public String getVideoId() {
        return videoId;
    }

    public int getPriority() {
        return priority;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public String getError() {
        return error;
    }

    public String getStatus() {
        return status;
    }

    public String getType() {
        return type;
    }

    public String getRequestedFragment() {
        return requestedFragment;
    }

    public int getCompletedFragments() {
        return completedFragments;
    }

    public int getTotalFragments() {
        return totalFragments;
    }

    public int getCancelRequested() {
        return cancelRequested;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setError(String error) {
        this.error = error;
    }

    public void setCompletedFragments(int completedFragments) {
        this.completedFragments = completedFragments;
    }

    public void setTotalFragments(int totalFragments) {
        this.totalFragments = totalFragments;
    }

    public void setCancelRequested(int cancelRequested) {
        this.cancelRequested = cancelRequested;
    }

    public String getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(String startedAt) {
        this.startedAt = startedAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(String completedAt) {
        this.completedAt = completedAt;
    }
}
