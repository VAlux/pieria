; Rust definitions and relations.
(struct_item name: (type_identifier) @def.name) @def.class
(union_item name: (type_identifier) @def.name) @def.class
(enum_item name: (type_identifier) @def.name) @def.enum
(trait_item name: (type_identifier) @def.name) @def.interface
(type_item name: (type_identifier) @def.name) @def.type_alias
(mod_item name: (identifier) @def.name) @def.module
(function_item name: (identifier) @def.name) @def.function
(declaration_list (function_item name: (identifier) @def.name) @def.method)

(use_declaration argument: (_) @ref.name) @ref.import
(call_expression function: (identifier) @ref.name) @ref.call
(call_expression function: (field_expression field: (field_identifier) @ref.name)) @ref.call
(macro_invocation macro: (identifier) @ref.name) @ref.call
