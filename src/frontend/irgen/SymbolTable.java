package frontend.irgen;

import ir.value.Value;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

public class SymbolTable {
    private LinkedList<Map<String, Value>> scopes;

    public SymbolTable() {
        scopes = new LinkedList<>();
        enterScope();
    }


    public void enterScope() {
        scopes.push(new HashMap<>());
    }
    public void exitScope() {
        if (scopes.size() > 1) {
            scopes.pop();
        } else {
            throw new RuntimeException("Cannot exit global scope.");
        }
    }

    public void define(String name, Value value) {
        scopes.peek().put(name, value);
    }

    public Value lookup(String name) {
        for (Map<String, Value> scope : scopes) {
            if (scope.containsKey(name)) {
                return scope.get(name);
            }
        }
        return null;
    }
    /**
     * Redefines an existing symbol in the symbol table. It searches from the current scope
     * outwards to the global scope. Once found, it updates the symbol's value.
     *
     * @param name The name of the symbol to redefine.
     * @param newValue The new Value to associate with the symbol.
     * @throws RuntimeException If the symbol is not found in any scope.
     */
    public void redefine(String name, Value newValue) {
        // Assert: The symbol must already exist in some scope to be redefined.
        // The current implementation already throws an exception if not found,
        // but an explicit assert can make the intent clearer for debugging.
        Value oldValue = lookup(name);
        assert oldValue != null : "Attempted to redefine a non-existent symbol: " + name;
        
        // Assert: In the current use case (materializing ConstantArray), oldValue is expected to be a ConstantArray.
        assert oldValue instanceof ir.value.constants.ConstantArray : "redefine expects oldValue to be a ConstantArray for this use case, but got: " + oldValue.getClass().getSimpleName();

        // Assert: In the current use case (materializing ConstantArray), newValue is expected to be a GlobalVariable.
        // This assert helps catch incorrect usage if redefine is called with other Value types.
        assert newValue instanceof ir.value.GlobalVariable : "redefine expects newValue to be a GlobalVariable for this use case, but got: " + newValue.getClass().getSimpleName();

        for (Map<String, Value> scope : scopes) {
            if (scope.containsKey(name)) {
                scope.put(name, newValue);
                return;
            }
        }
        // This line should ideally not be reached if the initial assert passes.
        throw new RuntimeException("Attempted to redefine undefined symbol: " + name);
    }

    public boolean isDefinedInCurrentScope(String name) {
        return scopes.peek().containsKey(name);
    }

    public String getDetailedString() {
        String str = "SymbolTable:\n";
        for (Map<String, Value> scope : scopes) {
            str += "Scope:\n";
            for (Map.Entry<String, Value> entry : scope.entrySet()) {
                str += "\t" + entry.getKey() + ": " + entry.getValue().getType() + "\n";
            }
        }

        return str;
    }
}