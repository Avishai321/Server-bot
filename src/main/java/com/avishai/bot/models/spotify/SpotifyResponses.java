package com.avishai.bot.models.spotify;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class SpotifyResponses {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Track(String name, List<Artist> artists, Album album) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Artist(String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Album(String name, @JsonProperty("release_date") String releaseDate) {}
}
