package com.eonmux.chatllm.models.inputs;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

import java.util.List;

/** The custom window for scheduled notifications. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Name("NotificationScheduleInput")
public class NotificationScheduleInput {
    private String startTime;
    private String endTime;
    private List<Integer> daysOfWeek;
}
