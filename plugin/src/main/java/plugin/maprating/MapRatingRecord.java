package plugin.maprating;

public record MapRatingRecord(
        String mapName,
        int star1,
        int star2,
        int star3,
        int star4,
        int star5,
        int votes,
        float rating
) {
    public int[] stars() {
        return new int[]{star1, star2, star3, star4, star5};
    }
}
