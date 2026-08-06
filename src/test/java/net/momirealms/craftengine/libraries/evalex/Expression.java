package net.momirealms.craftengine.libraries.evalex;

import net.momirealms.craftengine.libraries.evalex.data.EvaluationValue;

public class Expression {
    public Expression(String expression) {
    }

    public EvaluationValue evaluate() throws EvaluationException {
        return new EvaluationValue();
    }
}
