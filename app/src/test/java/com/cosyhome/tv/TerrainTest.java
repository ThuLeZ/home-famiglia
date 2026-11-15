package com.cosyhome.tv;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Heights from Open-Meteo for real places (the place, then 8 points at 2.5, 6 and 12 km). */
public class TerrainTest {

    private static Land.Kind at(double... h) {
        return Terrain.classify(h);
    }

    @Test
    public void sea() {
        assertEquals(Land.Kind.SEA, at(42, 363, 180, 32, 16, 0, 0, 23, 215, 410, 282, 321, 0, 0, 0, 0, 93, 313, 859,
                756, 0, 0, 0, 0, 449));                                                         // Genova
        assertEquals(Land.Kind.SEA, at(15, 0, 0, 0, 3, 12, 9, 3, 5, 0, 0, 0, 8, 30, 84, 23, 4, 0, 0, 0, 14, 120, 180,
                43, 2));                                                                        // Rimini
    }

    @Test
    public void lake() {
        assertEquals(Land.Kind.LAKE, at(211, 197, 197, 270, 197, 578, 197, 287, 197, 197, 646, 924, 431, 834, 207,
                1038, 369, 722, 1283, 499, 1013, 597, 197, 826, 688));                          // Bellagio
        assertEquals(Land.Kind.LAKE, at(84, 514, 334, 65, 143, 151, 332, 182, 258, 902, 705, 65, 65, 131, 287, 399,
                276, 1066, 529, 65, 65, 65, 176, 498, 521));                                    // Salò
    }

    @Test
    public void mountains() {
        assertEquals(Land.Kind.MOUNTAINS, at(1228, 1458, 1613, 1547, 1427, 1117, 1530, 1674, 1479, 1536, 2618, 2186,
                1625, 1882, 1738, 2736, 2035, 2409, 2309, 1311, 1897, 2106, 1665, 1926, 1916)); // Cortina
        assertEquals(Land.Kind.MOUNTAINS, at(279, 556, 1042, 424, 895, 835, 251, 248, 778, 905, 1339, 567, 798, 401,
                392, 243, 1174, 1749, 1603, 946, 1354, 1206, 484, 1688, 1153));                 // Bolzano
        assertEquals(Land.Kind.MOUNTAINS, at(209, 807, 330, 417, 222, 407, 591, 259, 197, 1272, 1315, 1302, 418, 650,
                223, 1003, 197, 2136, 1052, 1202, 923, 379, 260, 977, 361));                    // Lecco
    }

    @Test
    public void plains() {
        assertEquals(Land.Kind.PLAINS, at(142, 128, 133, 122, 124, 114, 116, 126, 129, 138, 125, 118, 107, 105, 109,
                123, 135, 171, 140, 109, 97, 96, 107, 129, 155));                               // Milano
    }

    @Test
    public void hills() {
        assertEquals(Land.Kind.HILLS, at(342, 303, 328, 267, 212, 213, 288, 289, 347, 259, 313, 232, 172, 204, 236,
                337, 268, 525, 326, 234, 207, 343, 196, 574, 197));                             // Siena
        assertEquals(Land.Kind.HILLS, at(62, 74, 64, 55, 117, 120, 91, 44, 49, 205, 317, 75, 89, 180, 128, 38, 36,
                637, 412, 361, 403, 204, 149, 45, 53));                                         // Firenze
        assertEquals(Land.Kind.HILLS, at(58, 68, 44, 40, 44, 43, 49, 24, 53, 16, 23, 28, 47, 36, 64, 90, 91, 51, 56,
                60, 86, 62, 27, 63, 118));                                                      // Roma
    }
}
