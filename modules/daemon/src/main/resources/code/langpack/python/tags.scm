; Python definitions and relations.
(class_definition name: (identifier) @def.name) @def.class
(function_definition name: (identifier) @def.name) @def.function
(class_definition body: (block (function_definition name: (identifier) @def.name) @def.method))
(import_statement name: (_) @ref.name) @ref.import
(import_from_statement module_name: (_) @ref.name) @ref.import
(class_definition superclasses: (argument_list (identifier) @ref.name)) @ref.extends
(call function: (identifier) @ref.name) @ref.call
(call function: (attribute attribute: (identifier) @ref.name)) @ref.call
