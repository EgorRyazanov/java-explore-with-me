package ru.practicum.ewm.compilation.mapper;

import org.springframework.stereotype.Component;
import ru.practicum.ewm.compilation.dto.CompilationDto;
import ru.practicum.ewm.compilation.model.Compilation;
import ru.practicum.ewm.event.dto.EventShortDto;

import java.util.List;

@Component
public class CompilationMapper {

    public CompilationDto toDto(Compilation compilation, List<EventShortDto> events) {
        return new CompilationDto(compilation.getId(), compilation.isPinned(), compilation.getTitle(), events);
    }
}
