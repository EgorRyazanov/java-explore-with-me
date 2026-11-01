package ru.practicum.ewm.compilation.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.ewm.compilation.dto.CompilationDto;
import ru.practicum.ewm.compilation.dto.NewCompilationDto;
import ru.practicum.ewm.compilation.dto.UpdateCompilationRequest;
import ru.practicum.ewm.compilation.mapper.CompilationMapper;
import ru.practicum.ewm.compilation.model.Compilation;
import ru.practicum.ewm.compilation.repository.CompilationRepository;
import ru.practicum.ewm.compilation.repository.CompilationRepository.CompilationEventReference;
import ru.practicum.ewm.event.dto.EventShortDto;
import ru.practicum.ewm.event.service.EventService;
import ru.practicum.ewm.exception.NotFoundException;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class CompilationService {

    private final CompilationRepository compilationRepository;
    private final CompilationMapper compilationMapper;
    private final EventService eventService;

    public CompilationService(CompilationRepository compilationRepository, CompilationMapper compilationMapper,
                              EventService eventService) {
        this.compilationRepository = compilationRepository;
        this.compilationMapper = compilationMapper;
        this.eventService = eventService;
    }

    @Transactional
    public CompilationDto createCompilation(NewCompilationDto request) {
        List<EventShortDto> events = resolveEvents(request.events());
        Compilation compilation = compilationRepository.save(new Compilation(request.title(), Boolean.TRUE.equals(request.pinned())));
        addEvents(compilation.getId(), events.stream().map(EventShortDto::id).toList());
        return compilationMapper.toDto(compilation, events);
    }

    public List<CompilationDto> getCompilations(Boolean pinned, int from, int size) {
        return toPublicDtos(compilationRepository.findCompilations(pinned == null, Boolean.TRUE.equals(pinned), from, size));
    }

    public CompilationDto getCompilation(long id) {
        Compilation compilation = compilationRepository.findById(id).orElseThrow(() -> notFound(id));
        return toPublicDtos(List.of(compilation)).getFirst();
    }

    @Transactional
    public CompilationDto updateCompilation(long id, UpdateCompilationRequest request) {
        Compilation compilation = compilationRepository.findByIdForUpdate(id).orElseThrow(() -> notFound(id));
        List<EventShortDto> events;
        if (request.events() == null) {
            List<Long> ids = compilationRepository.findEventReferences(List.of(id)).stream()
                    .map(CompilationEventReference::getEventId).toList();
            events = eventService.getShortEventsByIds(ids);
        } else {
            events = resolveEvents(request.events());
        }
        if (request.title() != null) {
            compilation.rename(request.title());
        }
        if (request.pinned() != null) {
            compilation.setPinned(request.pinned());
        }
        if (request.events() != null) {
            compilationRepository.removeEvents(id);
            addEvents(id, events.stream().map(EventShortDto::id).toList());
        }
        return compilationMapper.toDto(compilation, events);
    }

    @Transactional
    public void deleteCompilation(long id) {
        if (compilationRepository.deleteCompilation(id) == 0) {
            throw notFound(id);
        }
    }

    private List<CompilationDto> toPublicDtos(List<Compilation> compilations) {
        if (compilations.isEmpty()) {
            return List.of();
        }
        List<CompilationEventReference> references = compilationRepository
                .findEventReferences(compilations.stream().map(Compilation::getId).toList());
        List<Long> eventIds = references.stream().map(CompilationEventReference::getEventId).distinct().sorted().toList();
        Map<Long, EventShortDto> events = eventService.getPublishedShortEventsByIds(eventIds).stream()
                .collect(Collectors.toMap(EventShortDto::id, event -> event));
        Map<Long, List<Long>> membership = references.stream().collect(Collectors.groupingBy(
                CompilationEventReference::getCompilationId,
                Collectors.mapping(CompilationEventReference::getEventId, Collectors.toList())));
        return compilations.stream().map(compilation -> compilationMapper.toDto(compilation,
                membership.getOrDefault(compilation.getId(), List.of()).stream().map(events::get)
                        .filter(Objects::nonNull).toList())).toList();
    }

    private List<EventShortDto> resolveEvents(Set<Long> ids) {
        List<Long> selected = ids == null ? List.of() : ids.stream().sorted().toList();
        List<EventShortDto> events = eventService.getShortEventsByIds(selected);
        if (events.size() != selected.size()) {
            throw new NotFoundException("Some events in the compilation were not found");
        }
        return events;
    }

    private void addEvents(long id, List<Long> events) {
        if (!events.isEmpty() && compilationRepository.addEvents(id, events) != events.size()) {
            throw new NotFoundException("Some events in the compilation were not found");
        }
    }

    private NotFoundException notFound(long id) {
        return new NotFoundException("Compilation with id=" + id + " was not found");
    }
}
