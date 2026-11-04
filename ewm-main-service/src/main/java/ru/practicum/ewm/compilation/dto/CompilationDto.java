package ru.practicum.ewm.compilation.dto;

import ru.practicum.ewm.event.dto.EventShortDto;

import java.util.List;

public record CompilationDto(Long id, boolean pinned, String title, List<EventShortDto> events) {
}
