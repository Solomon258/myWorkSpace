package com.icecode.workbench.favorite;

import java.util.List;

import javax.validation.Valid;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.icecode.workbench.common.ApiResponse;

@RestController
@RequestMapping("/api/v1/favorites")
public class FavoriteController {

    private final FavoriteService favoriteService;

    public FavoriteController(FavoriteService favoriteService) {
        this.favoriteService = favoriteService;
    }

    @GetMapping
    public ApiResponse<List<FavoriteVO>> list(
            @RequestParam(required = false) String grp,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean archived) {
        return ApiResponse.success(favoriteService.list(grp, q, archived));
    }

    @PostMapping
    public ApiResponse<FavoriteVO> create(@Valid @RequestBody FavoriteCreateRequest request) {
        return ApiResponse.success(favoriteService.create(request));
    }

    @PatchMapping("/{id}")
    public ApiResponse<FavoriteVO> update(@PathVariable long id, @Valid @RequestBody FavoriteUpdateRequest request) {
        return ApiResponse.success(favoriteService.update(id, request));
    }

    @PostMapping("/{id}/pin")
    public ApiResponse<FavoriteVO> togglePin(@PathVariable long id) {
        return ApiResponse.success(favoriteService.togglePin(id));
    }

    @PostMapping("/{id}/archive")
    public ApiResponse<FavoriteVO> toggleArchive(@PathVariable long id) {
        return ApiResponse.success(favoriteService.toggleArchive(id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        favoriteService.delete(id);
        return ApiResponse.success(null);
    }
}
