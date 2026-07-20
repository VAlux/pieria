; PHP definitions and relations.
(namespace_definition name: (namespace_name) @def.name) @def.package
(interface_declaration name: (name) @def.name) @def.interface
(trait_declaration name: (name) @def.name) @def.interface
(class_declaration name: (name) @def.name) @def.class
(enum_declaration name: (name) @def.name) @def.enum
(function_definition name: (name) @def.name) @def.function
(method_declaration name: (name) @def.name) @def.method
(property_declaration (property_element (variable_name (name) @def.name))) @def.field

(namespace_use_declaration (namespace_name) @ref.name) @ref.import
(class_declaration (base_clause (_) @ref.name)) @ref.extends
(class_interface_clause (_) @ref.name) @ref.implements
(function_call_expression function: [(name) (qualified_name)] @ref.name) @ref.call
(scoped_call_expression name: (name) @ref.name) @ref.call
(member_call_expression name: (name) @ref.name) @ref.call
