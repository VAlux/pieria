; Ruby definitions and calls.
(class name: [(constant) (scope_resolution)] @def.name) @def.class
(singleton_class value: [(constant) (scope_resolution)] @def.name) @def.class
(module name: [(constant) (scope_resolution)] @def.name) @def.module
(method name: (_) @def.name) @def.method
(singleton_method name: (_) @def.name) @def.method
(alias name: (_) @def.name) @def.method

(call method: (identifier) @ref.name) @ref.call
