package io.github.greytaiwolf.botplayer.skill.core;

public enum SkillParameterType {
    STRING {
        @Override
        public boolean accepts(Object value) {
            return value instanceof String;
        }
    },
    INTEGER {
        @Override
        public boolean accepts(Object value) {
            return value instanceof Integer;
        }
    },
    LONG {
        @Override
        public boolean accepts(Object value) {
            return value instanceof Integer || value instanceof Long;
        }
    },
    DECIMAL {
        @Override
        public boolean accepts(Object value) {
            return value instanceof Integer
                    || value instanceof Long
                    || value instanceof Double;
        }
    },
    BOOLEAN {
        @Override
        public boolean accepts(Object value) {
            return value instanceof Boolean;
        }
    };

    public abstract boolean accepts(Object value);
}
