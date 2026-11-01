package ru.practicum.ewm.user.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.user.dto.NewUserRequest;
import ru.practicum.ewm.user.dto.UserDto;
import ru.practicum.ewm.user.mapper.UserMapper;
import ru.practicum.ewm.user.model.User;
import ru.practicum.ewm.user.repository.UserRepository;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;

    public UserService(UserRepository userRepository, UserMapper userMapper) {
        this.userRepository = userRepository;
        this.userMapper = userMapper;
    }

    @Transactional
    public UserDto createUser(NewUserRequest request) {
        return userMapper.toDto(userRepository.save(new User(request.name(), request.email())));
    }

    public List<UserDto> getUsers(List<Long> ids, int from, int size) {
        List<Long> selectedIds = ids == null ? List.of() : ids;
        return userRepository.findUsers(selectedIds, selectedIds.isEmpty(), from, size).stream()
                .map(userMapper::toDto)
                .toList();
    }

    @Transactional
    public void deleteUser(long userId) {
        if (userRepository.deleteUser(userId) == 0) {
            throw new NotFoundException("User with id=" + userId + " was not found");
        }
    }
}
